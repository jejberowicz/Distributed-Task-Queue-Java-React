#!/usr/bin/env sh
# Llena la cola contra la demo de AWS para que el autoscaler tenga qué escalar.
#
# Es el mismo script que el Job de k8s/loadgen, con dos diferencias: corre desde
# la máquina de uno y no dentro del cluster, y pega contra el ALB en vez de
# contra el Service. Lo demás —una key nueva por lote, el payload armado a mano,
# la Idempotency-Key distinta por lote— es idéntico y por los mismos motivos.
#
#   API=$(terraform output -raw url) ADMIN_TOKEN=$(terraform output -raw admin_token) sh loadgen.sh
set -eu

: "${API:?falta API (terraform output -raw url)}"
: "${ADMIN_TOKEN:?falta ADMIN_TOKEN (terraform output -raw admin_token)}"
JOBS="${JOBS:-300}"
BATCH=100

# Una API key nueva por lote. El rate limit del tier PREMIUM es 300 req/min y el
# batch cobra una unidad por job, así que una sola key se quedaría sin cuota a
# mitad de la corrida y el 429 arruinaría la demo.
new_key() {
  curl -sf -X POST "$API/admin/api-keys" \
    -H "X-Admin-Token: $ADMIN_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"name":"loadgen","tier":"PREMIUM"}' \
    | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p'
}

payload() {
  n="$1"
  printf '{"jobs":['
  i=0
  while [ "$i" -lt "$n" ]; do
    if [ "$i" -gt 0 ]; then printf ','; fi
    printf '{"model":"llama3","type":"COMPLETION","prompt":"carga %s-%s","ttlSeconds":900}' "$2" "$i"
    i=$((i + 1))
  done
  printf ']}'
}

sent=0
batch_no=0
while [ "$sent" -lt "$JOBS" ]; do
  remaining=$((JOBS - sent))
  size=$BATCH
  # Explícito y no `[ ... ] && size=...`: con `set -e`, una lista && que termina
  # en falso es una de las formas más fáciles de matar el script.
  if [ "$remaining" -lt "$BATCH" ]; then size=$remaining; fi
  key=$(new_key)
  payload "$size" "$batch_no" | curl -sf -o /dev/null -X POST "$API/v1/jobs/batch" \
    -H "Authorization: Bearer $key" \
    -H "Idempotency-Key: loadgen-$(date +%s)-$batch_no" \
    -H 'Content-Type: application/json' \
    --data-binary @-
  sent=$((sent + size))
  batch_no=$((batch_no + 1))
  echo "encolados $sent/$JOBS"
done

echo
echo "listo. La métrica tarda hasta 1 minuto en aparecer y la alarma otro tanto:"
echo "  make watch"
