# InferQueue

Plataforma de AI inference asíncrona. Submitís un job por REST, se encola en Redis Streams, un
pool de workers lo procesa contra Ollama y el resultado llega al dashboard en tiempo real por
WebSocket. Es, en chico, lo que hacen Replicate o Together.ai por dentro.

**Stack:** Java 21 + Spring Boot 3 · Redis Streams · PostgreSQL · React + Vite · STOMP/WebSocket · Docker Compose

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
`infer:priority`, los free a `infer:standard`. También mantiene los WebSockets del dashboard.

**Redis Streams** — consumer group `inferqueue-workers` sobre ambos streams. `XREADGROUP` deja el
mensaje en el Pending Entry List (PEL) del consumer hasta que hace `XACK`; ahí está el
at-least-once. Un stream aparte (`infer:dlq`) recibe lo que agotó reintentos.

**Workers** — un virtual thread por worker, cada uno con su propio consumer name. Hablan con el
modelo detrás de la interfaz `ModelAdapter`, que tiene dos implementaciones: `OllamaAdapter` (HTTP
contra Ollama) y `MockAdapter` (latencia y fallos simulados, para desarrollo y demos sin GPU).

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
  -d '{"model":"llama3","type":"COMPLETION","prompt":"explicá XCLAIM","ttlSeconds":300}'
```

Para usar inference real contra el Ollama del host: `MODEL_ADAPTER=ollama docker compose up`.

Sin Docker: `cd backend && mvn spring-boot:run` (necesita Postgres y Redis locales) y
`cd dashboard && npm install && npm run dev`.

### API

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/v1/jobs` | Encola un job (`model`, `type`, `prompt`, `priority?`, `ttlSeconds?`) |
| `GET` | `/v1/jobs/{id}` | Estado y resultado de un job |
| `GET` | `/v1/jobs?status=&page=&size=` | Listado propio de la API key, paginado |
| `GET` | `/v1/jobs/usage` | Tokens consumidos en los últimos 30 días |
| `GET` | `/v1/stats` | Conteos por estado, profundidad de streams, PEL, DLQ |
| `POST` | `/admin/api-keys` | Emite una API key (requiere `X-Admin-Token`) |
| STOMP | `/ws` → `/topic/jobs` | Eventos de cambio de estado en vivo |

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
- `/topic/jobs` es global y el filtrado por API key se hace en el cliente: sirve para el dashboard
  propio, no para multi-tenant real. La versión seria necesita autenticación en el CONNECT de STOMP
  y un topic por key.
- Los streams se limpian borrando el mensaje después del ack; no hay `XTRIM` por retención.

## Tests

```bash
cd backend && mvn test
```

17 tests sobre lo que puede romperse de verdad: reentrega de un job terminal tratada como no-op,
ack antes de reprogramar el retry, corte a la DLQ al agotar intentos o ante un error no
reintentable, job vencido que no se ejecuta, curva de backoff con su techo, y que el key en claro
nunca se persiste.
