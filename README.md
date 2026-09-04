# InferQueue

Plataforma de AI inference asíncrona. Submitís un job por REST, se encola en Redis Streams, un
pool de workers lo procesa contra Ollama y el resultado llega al dashboard en tiempo real por
WebSocket. Es, en chico, lo que hacen Replicate o Together.ai por dentro.

**Stack:** Java 21 + Spring Boot 3 · Redis Streams · PostgreSQL · React + Vite · STOMP/WebSocket · Docker Compose · Kubernetes (kind) · AWS con Terraform

---

## Arquitectura

```
                  ┌───────────────┐
   REST /v1/jobs  │    Gateway    │  STOMP /ws
  ───────────────▶│  Spring Boot  │◀────────────── Dashboard (React)
                  │ auth · rate   │
                  │ limit · WS    │
                  └───┬───────┬───┘
             XADD     │       │  pub/sub jobs:events
                      ▼       ▲
        ┌─────────────────────┴──────────┐
        │            Redis               │
        │  infer:priority  infer:standard│
        │  infer:dlq  infer:delayed(ZSET)│
        └───────┬────────────────────────┘
      XREADGROUP│  XACK / XCLAIM
                ▼
        ┌────────────────┐      ┌──────────┐
        │    Workers     │─────▶│  Ollama  │
        │ virtual threads│      └──────────┘
        └───────┬────────┘
                │ estado + resultado
                ▼
        ┌────────────────┐
        │   PostgreSQL   │
        └────────────────┘
```

Tres piezas independientes:

**Gateway** — autentica por API key (SHA-256 en Postgres, nunca el key en claro), aplica rate limit
por tier con un contador en Redis, y encola con prioridad: los clientes premium van a
`infer:priority`, los free a `infer:standard`. También mantiene los WebSockets del dashboard, que
se autentican en el frame CONNECT y quedan acotados al topic de su propia key.

**Redis Streams** — consumer group `inferqueue-workers` sobre ambos streams. `XREADGROUP` deja el
mensaje en el Pending Entry List (PEL) del consumer hasta que hace `XACK`; ahí está el
at-least-once. Un stream aparte (`infer:dlq`) recibe lo que agotó reintentos.

**Workers** — un virtual thread por worker, cada uno con su propio consumer name. Hablan con el
modelo detrás de la interfaz `ModelAdapter`, que tiene dos implementaciones: `OllamaAdapter` (HTTP
contra Ollama) y `MockAdapter` (latencia y fallos simulados, para desarrollo y demos sin GPU). La
respuesta se emite token a token mientras se genera, así el dashboard la ve escribiéndose.

---

## Correr el proyecto

```bash
docker compose up --build          # postgres, redis, gateway, 2 workers, dashboard
```

Dashboard en http://localhost:5173, API en http://localhost:8080.

Emitir una API key y encolar un job:

```bash
curl -s -X POST localhost:8080/admin/api-keys \
  -H 'X-Admin-Token: dev-admin-token' -H 'Content-Type: application/json' \
  -d '{"name":"cli","tier":"PREMIUM"}'
# => {"apiKey":"iq_…"}   ← se muestra una sola vez

curl -s -X POST localhost:8080/v1/jobs \
  -H 'Authorization: Bearer iq_…' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: mi-request-1' \
  -d '{"model":"llama3","type":"COMPLETION","prompt":"explicá XCLAIM","ttlSeconds":300}'
```

Cancelarlo, o mirar qué murió en la DLQ:

```bash
curl -s -X DELETE localhost:8080/v1/jobs/{id} -H 'Authorization: Bearer iq_…'
curl -s localhost:8080/admin/dlq -H 'X-Admin-Token: dev-admin-token'
```

Para usar inference real contra el Ollama del host: `MODEL_ADAPTER=ollama docker compose up`.

Sin Docker: `cd backend && mvn spring-boot:run` (necesita Postgres y Redis locales) y
`cd dashboard && npm install && npm run dev`.

### En Kubernetes

El mismo sistema descrito como se describiría en producción, corriendo en un cluster local:

```bash
cd k8s && make up        # kind + ingress + KEDA + deploy → http://localhost
make loadgen             # encola 300 jobs
make watch               # los workers escalan de 1 a 10 y vuelven a bajar
```

Lo que cambia respecto de compose no es el empaquetado sino el autoscaling: **los workers
escalan por el backlog de la cola, no por CPU.** Un worker esperando la respuesta del modelo
está ocioso en CPU mientras la cola crece, así que un HPA por utilización escalaría justo al
revés de lo que hace falta. KEDA lee el largo del stream de Redis y alimenta con eso un HPA
normal — que acá es el backlog real, porque el worker borra cada mensaje al ackearlo. El gateway sí escala por CPU: su trabajo es síncrono y ahí la utilización sí
correlaciona.

El detalle —probes, arranque ordenado sin `depends_on`, apagado que no pierde jobs en vuelo,
Secrets, Ingress con WebSocket— está en [`k8s/README.md`](k8s/README.md).

### En AWS

La misma cosa, con la infraestructura como código y una URL pública. ECS Fargate para el
cómputo, RDS y ElastiCache para los datos, un ALB adelante.

```bash
cd terraform && make up   # ECR + push de imágenes + toda la infra
make loadgen              # encola 300 jobs
make watch                # la cola, las alarmas, y el worker escalando
make down                 # destruye todo
```

Las imágenes son las mismas: no hay una línea de Java distinta entre compose, kind y AWS.
Lo que cambia es quién agenda los contenedores y, sobre todo, **quién mide la cola**. KEDA
no existe fuera de Kubernetes, así que el autoscaling se rearma con las piezas de AWS: una
Lambda de veinte líneas hace `XLEN` cada minuto y publica el backlog como métrica de
CloudWatch, y una alarma sobre esa métrica dispara una política de step scaling. La métrica
es la misma y por el mismo motivo; lo que se paga es un minuto de latencia de reacción en
vez de quince segundos.

Los workers corren en Fargate Spot, que cuesta 70% menos a cambio de que AWS pueda matarlos
con dos minutos de aviso. Para este sistema eso no es un riesgo nuevo: es exactamente el
caso que el PEL y el `XCLAIM` ya resuelven.

No es gratis —unos 0.07 USD/hora, sobre todo por el ALB y por Fargate, que no tienen free
tier— así que está pensado para levantarlo, mostrarlo y destruirlo. El desglose, el mapeo
pieza por pieza contra la Fase 1 y las trampas que costaron tiempo están en
[`terraform/README.md`](terraform/README.md).

### API

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/v1/jobs` | Encola un job (`model`, `type`, `prompt`, `priority?`, `ttlSeconds?`) |
| `POST` | `/v1/jobs/batch` | Encola hasta 100 jobs en una transacción |
| `GET` | `/v1/jobs/{id}` | Estado y resultado de un job |
| `DELETE` | `/v1/jobs/{id}` | Cancela un job que todavía no terminó |
| `GET` | `/v1/jobs?status=&page=&size=` | Listado propio de la API key, paginado |
| `GET` | `/v1/jobs/usage` | Tokens consumidos en los últimos 30 días |
| `GET` | `/v1/stats` | Conteos por estado, profundidad de streams, PEL, DLQ |
| `POST` | `/admin/api-keys` | Emite una API key (requiere `X-Admin-Token`) |
| `GET` | `/admin/dlq` | Jobs muertos con su motivo e intentos |
| `POST` | `/admin/dlq/{recordId}/requeue` | Devuelve un job muerto a la cola |
| `DELETE` | `/admin/dlq/{recordId}` · `/admin/dlq` | Descarta una entrada o vacía la DLQ |
| STOMP | `/ws` → `/topic/keys/{apiKeyId}/jobs` | Eventos de estado en vivo |
| STOMP | `/ws` → `/topic/keys/{apiKeyId}/jobs/tokens` | Respuesta en streaming, fragmento a fragmento |

Los dos endpoints de submit aceptan `Idempotency-Key`: repetir el request devuelve `200` con el job
original en vez de `201` con uno nuevo.

Métricas Prometheus en `/actuator/prometheus`: jobs completados, reintentados, muertos, expirados,
reclamados por XCLAIM, y latencia de inference.

---

## Design decisions

### ¿Por qué Redis Streams y no RabbitMQ o Kafka?

Streams da at-least-once con el PEL sin sumar un broker más al deploy: Redis ya estaba para el rate
limiting y el pub/sub de los WebSockets. Kafka aportaría retención y particionado real, pero para
un sistema de este tamaño el costo operativo no se paga. La frontera está clara: cuando haga falta
replay histórico o throughput que no entre en una instancia, Streams se queda corto.

### ¿Qué pasa si un worker muere a mitad de un job?

El mensaje queda en el PEL sin `XACK`. `PendingReclaimer` corre cada 15s, lee el PEL con `XPENDING`
y reclama con `XCLAIM` todo lo que lleve más de `claim-idle-timeout` (60s por defecto) sin tocar.
Ese mensaje se reprocesa en otro worker.

El race condition es real y está asumido: si el timeout es más corto que la inference más lenta, un
worker **vivo pero ocupado** puede perder su mensaje y terminar duplicando trabajo con quien lo
reclamó. Se mitiga por dos lados:

1. `claim-idle-timeout` se configura por encima del p99 de la inference.
2. `JobExecutor` chequea el estado en Postgres antes de ejecutar: si el job ya está en estado
   terminal, la reentrega es un no-op ackeado. El peor caso es cómputo desperdiciado, nunca un
   resultado pisado.

En el shutdown ordenado pasa lo mismo a propósito: el pool deja de tomar mensajes nuevos y espera
45s a los que están en vuelo; lo que no alcance a ackearse queda en el PEL y lo levanta otra réplica.

### ¿Cómo se garantiza at-least-once?

Tres reglas, en este orden:

1. **El job se persiste antes de encolarse**, y el `XADD` se difiere al commit de la transacción.
   Al revés, un worker rápido podría buscar en la base un job que todavía no está commiteado.
2. **El `XACK` ocurre siempre al final**, después de dejar el job consistente en Postgres. Si el
   proceso muere entre el resultado y el ack, el mensaje se reentrega y el chequeo de estado
   terminal lo absorbe.
3. **El retry no revive el mensaje viejo**: se ackea el actual y se programa uno nuevo. Un mensaje
   esperando su backoff dentro del PEL bloquearía al reclaimer.

No hay exactly-once, y no se finge que lo haya: las operaciones se diseñaron para que la reentrega
sea inocua.

### ¿Por qué priority queue y no FIFO simple?

Los tiempos no son comparables entre tipos de job: un embedding tarda cientos de milisegundos y una
completion de 8k tokens en `llama3:70b` puede tardar minutos. En una FIFO única, un free tier
encolando prompts largos degrada a todos los demás. Los workers leen `infer:priority` sin bloquear
y sólo caen a `infer:standard` si está vacío — cuesta un round-trip extra por poll y a cambio
ningún premium espera detrás de un free.

Trade-off aceptado: bajo carga sostenida de prioridad, el stream standard se puede starvear. La
salida sería reservar una fracción de los workers para standard; hoy no está implementado porque
el rate limit por tier acota el caso.

### ¿Cómo se hace el backoff si Streams no tiene entrega diferida?

Con un sorted set (`infer:delayed`) donde el score es el timestamp en que el job vuelve a estar
listo, y un scheduler que promueve al stream lo vencido cada segundo. La promoción usa el resultado
del `ZREM` como candado: si hay varias instancias corriendo el scheduler, sólo la que gana el
remove encola. El backoff es `2s · 2^intento` con techo de 5 minutos, y a los 3 reintentos el job
va a la DLQ con el motivo del descarte.

### ¿Cómo se cancela un job que ya está en la cola?

No se cancela el mensaje: no hay forma de sacar una entrada ya escrita en un stream de Redis. La
cancelación es un estado en Postgres y el mensaje sigue su curso hasta que un worker lo toma y lo
descarta por estado terminal.

Lo interesante es la carrera contra el worker que ya lo está ejecutando. Todas las transiciones de
estado pasan por `SELECT ... FOR UPDATE` y abortan si el job ya está terminal, así que un worker que
termina su inference después de la cancelación descarta el resultado en vez de pisar el `CANCELED`
con un `DONE`. El retry sigue la misma regla: si la transición no prospera, no se programa.

Lo único que sí se limpia de verdad son los retries que todavía están esperando en el ZSET diferido,
porque esos todavía no llegaron al stream.

### ¿Cómo se evita cobrar dos veces la misma inference?

Con `Idempotency-Key`, apoyado en un índice único parcial `(api_key_id, idempotency_key)` y no en un
`SELECT` previo. La diferencia importa: dos requests simultáneos con la misma clave chocan contra el
índice, y el que pierde relee el job del ganador en vez de crear un duplicado. Por eso el insert
vive en un bean aparte — la violación de integridad recién aparece al commitear, y para capturarla
hay que cruzar el proxy transaccional.

El batch deriva una clave por ítem (`clave#indice`) y reusa el mismo índice. Además entra en una
sola transacción, así que un batch que falló no dejó nada encolado y reintentarlo es seguro sin más
ceremonia.

### ¿Por qué el WebSocket se autentica en el CONNECT y no en el handshake?

Porque el handshake HTTP del WebSocket no lleva el header `Authorization` en todos los clientes — el
navegador no deja setear headers en `new WebSocket()`. La credencial viaja entonces en el frame
CONNECT de STOMP, se resuelve contra `ApiKeyService` y queda como `Principal` de la sesión.

Cada SUBSCRIBE se compara contra ese principal: sólo se entra a `/topic/keys/{propia-key}/**`. Antes
el topic era global y el filtrado lo hacía el cliente, que es lo mismo que decir que no había
filtrado.

### ¿Por qué hay retención por XTRIM si los mensajes ya se borran al ackear?

Porque el camino feliz no es el único. Un ack perdido, un `XADD` que nunca se consumió o un consumer
group borrado dejan entradas que nadie va a sacar, y Redis crece hasta quedarse sin memoria.

El detalle a tener presente es que `XTRIM MAXLEN` descarta las entradas **más viejas**, que en un
stream de trabajo son justamente las que todavía no se procesaron. Por eso el techo se configura muy
por encima del backlog esperable y cada recorte efectivo se loguea como warning: si el trimmer está
recortando de verdad, el problema es que los workers no dan abasto.

### ¿Por qué el streaming de tokens va por un canal aparte?

Los fragmentos son muchos, son efímeros y perder uno no cambia nada — el resultado definitivo
siempre llega en el evento de `DONE`. Mezclarlos con los eventos de estado obligaría a tratarlos con
la misma seriedad que a una transición de estado, y no la merecen.

Aun así, emitir un mensaje de pub/sub por token inunda Redis sin que se note en pantalla, así que
`TokenStream` los acumula y los suelta por tamaño (48 caracteres) o por tiempo (250ms). Cada
fragmento lleva su `seq` y el cliente los indexa por número: el pub/sub no garantiza orden y
concatenar a ciegas mostraría la respuesta mezclada.

### ¿Por qué Ollama y no la API de OpenAI?

Costo cero en desarrollo, reproducible sin credenciales, y corre contra la GPU local. La interfaz
`ModelAdapter` mantiene esa decisión reversible: cambiar de backend es una implementación nueva, no
tocar la lógica de la cola. `MockAdapter` es el que hace que el proyecto se pueda demostrar en
cualquier máquina — simula latencia por tipo de job y una tasa de fallo del 15%, así el retry, el
backoff y la DLQ se ven funcionando de verdad (un prompt que empieza con `fail:` falla siempre).

### ¿Por qué el TTL se chequea en dos lugares?

El worker descarta el job vencido al tomarlo, pero un job encolado detrás de una cola larga podría
no ser tomado nunca. `TtlSweeper` los cierra igual cada 10s para que el cliente vea `EXPIRED` y no
un `QUEUED` eterno.

### ¿Por qué los eventos del dashboard pasan por Redis pub/sub?

Los workers corren en un proceso distinto al gateway que tiene los sockets abiertos, así que no
pueden pushear al broker STOMP directamente. Publican en el canal `jobs:events` y cada gateway
reenvía a sus clientes. Si esa publicación falla, el job igual sigue su curso: el dashboard se
recupera con el polling de `/v1/stats`.

---

## Límites conocidos

- El broker STOMP es en memoria; el fan-out escala hasta donde escale una instancia del gateway.
- El rate limit usa ventana fija, así que admite hasta 2x el límite en el borde entre ventanas. Con
  sliding window log el costo por request sube y no valía la pena acá.
- La cancelación no interrumpe la inference en curso: el worker la termina y descarta el resultado.
  Cortarla de verdad necesita que el `ModelAdapter` exponga cancelación, y Ollama no la tiene.
- El token de admin es uno solo y compartido para todo `/admin`. Alcanza para operar, no para
  auditar quién hizo qué.
- Los fragmentos de streaming no se persisten: si el dashboard se conecta a mitad de un job, ve el
  resultado recién al completarse.

## Tests

```bash
cd backend && mvn test          # necesita Docker: los de integración levantan Postgres y Redis
```

56 tests, en dos niveles.

**Unitarios** — la lógica que puede romperse sin infraestructura: reentrega de un job terminal
tratada como no-op, ack antes de reprogramar el retry, corte a la DLQ al agotar intentos o ante un
error no reintentable, job vencido que no se ejecuta, resultado descartado cuando el job se canceló
en pleno vuelo, curva de backoff con su techo, deduplicación por Idempotency-Key, autorización de
las suscripciones STOMP, y que el key en claro nunca se persiste.

**Integración con Testcontainers** — lo que no se puede testear con mocks sin terminar testeando el
mock: qué queda en el PEL de un consumer group, qué reclama `XCLAIM` y cuándo, si el índice único de
idempotencia frena de verdad ocho submits simultáneos, si el `SELECT ... FOR UPDATE` serializa las
transiciones. Incluye el escenario central del proyecto: un mensaje entregado y nunca ackeado —
exactamente lo que deja un worker que muere a mitad de un job — que no se puede reclamar antes del
idle timeout y que después levanta otro worker.
