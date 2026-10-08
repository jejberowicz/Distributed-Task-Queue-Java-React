#!/usr/bin/env sh
# Carga de prueba contra el compose local, para mirarla en el dashboard.
#
# A diferencia del loadgen de k8s y AWS —que crea una key nueva por lote para no
# chocar con el rate limit— acá todo va con UNA key: el dashboard sólo muestra los
# jobs de la key con la que uno entró, así que con keys descartables la tabla
# quedaría vacía. Por eso el tope de JOBS: el tier PREMIUM permite 300 req/min.
#
#   KEY=iq_… sh scripts/loadgen.sh            # 20 jobs con la key del dashboard
#   KEY=iq_… JOBS=50 FAILS=3 sh scripts/loadgen.sh
set -eu

API="${API:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-dev-admin-token}"
MODEL="${MODEL:-llama3.2:1b}"
JOBS="${JOBS:-20}"
# Jobs que fallan siempre, agotan los reintentos y terminan en la DLQ. Llevan las dos
# cosas que hacen fallar a cada adapter: el prompt "fail:" al mock, y un modelo que
# no existe a Ollama (que si no, contesta el "fail:" como cualquier otro prompt).
FAILS="${FAILS:-2}"
# Pausa entre envíos, para que en el dashboard se vea la cola llenándose y no un salto.
DELAY="${DELAY:-0.3}"

if [ "$JOBS" -gt 250 ]; then
  echo "JOBS=$JOBS pasa el rate limit de una key PREMIUM (300/min); usá 250 o menos." >&2
  exit 1
fi

if [ -z "${KEY:-}" ]; then
  KEY=$(curl -sf -X POST "$API/admin/api-keys" \
    -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d '{"name":"loadgen","tier":"PREMIUM"}' \
    | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p')
  echo "Sin KEY: creé una nueva. Para ver estos jobs, entrá al dashboard con:"
  echo "  $KEY"
  echo
fi

# Respuestas cortas a propósito: con el modelo en CPU, un prompt que pide un
# ensayo tarda medio minuto y la corrida se vuelve eterna.
COMPLETIONS='Explicá en una oración qué es Redis.
Dame un sinónimo de rápido.
En una oración, qué es una cola de mensajes.
Decime un dato curioso sobre los pulpos en una oración.
Traducí al inglés: el perro duerme en el sillón.
En una oración, para qué sirve Docker.
Nombrá tres lenguajes de programación.
Resumí en una oración qué hace un balanceador de carga.
Escribí un haiku sobre el café.
Cuál es la capital de Australia. Respondé solo el nombre.
En una oración, qué es la latencia.
Dame un nombre para un gato naranja.'

CLASSIFICATIONS='Me encantó la película, la volvería a ver.
El envío llegó tarde y roto.
El servicio fue excelente y muy rápido.
No funciona nada, quiero mi plata de vuelta.
Está bien, nada del otro mundo.'

pick() {
  # Una línea al azar de la lista; awk porque `shuf` no está en todos lados.
  printf '%s\n' "$1" | awk -v seed="$(date +%s%N)" 'BEGIN{srand(substr(seed, length(seed)-8))} {l[NR]=$0} END{print l[int(rand()*NR)+1]}'
}

run="$(date +%s)"
ok=0
i=1
while [ "$i" -le "$JOBS" ]; do
  # Uno de cada cuatro es una clasificación; el resto, completions.
  if [ $((i % 4)) -eq 0 ]; then
    type=CLASSIFICATION; prompt=$(pick "$CLASSIFICATIONS")
  else
    type=COMPLETION; prompt=$(pick "$COMPLETIONS")
  fi
  # Exactamente FAILS fallos forzados, repartidos a lo largo de la corrida: el job i
  # falla cuando i*FAILS/JOBS cruza un entero.
  if [ $((i * FAILS / JOBS)) -gt $(((i - 1) * FAILS / JOBS)) ]; then
    type=COMPLETION; prompt="fail: job $i, para ver la DLQ"; model=no-existe
  else
    model="$MODEL"
  fi
  # Un tercio por la cola standard aunque la key sea premium: así se ven los dos streams.
  if [ $((i % 3)) -eq 0 ]; then priority=',"priority":"STANDARD"'; else priority=''; fi

  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/v1/jobs" \
    -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: loadgen-$run-$i" \
    -d "{\"model\":\"$model\",\"type\":\"$type\",\"prompt\":\"$prompt\",\"ttlSeconds\":900$priority}")
  if [ "$code" = 202 ] || [ "$code" = 201 ] || [ "$code" = 200 ]; then
    ok=$((ok + 1))
    printf '  %3s/%s  %-14s %s\n' "$i" "$JOBS" "$type" "$prompt"
  else
    printf '  %3s/%s  HTTP %s  %s\n' "$i" "$JOBS" "$code" "$prompt" >&2
  fi
  i=$((i + 1))
  sleep "$DELAY"
done

echo
echo "encolados $ok/$JOBS. Miralo en http://localhost:5173"
