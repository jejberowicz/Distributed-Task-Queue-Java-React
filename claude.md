# InferQueue — estado del proyecto

Notas para retomar el desarrollo más adelante. El README explica **qué hace** el sistema y **por
qué** está diseñado así; esto es lo otro: dónde está cada cosa, qué no hay que romper, y qué falta.

> La conversación original donde se eligió el dominio y se bocetó la arquitectura está en el
> historial de git: `git show b8414cc:claude.md`.

---

## Qué es

Plataforma de inferencia de IA asíncrona. El cliente encola un job por REST, recibe un `jobId` al
instante, y el trabajo ocurre en workers separados que hablan con Ollama. El resultado llega al
dashboard por WebSocket, token a token.

La cola no existe porque el productor sea rápido: existe porque el consumidor es lento. Una
inferencia tarda entre segundos y minutos, y no se puede sostener un request HTTP abierto todo ese
tiempo. Todo lo demás —reintentos, DLQ, TTL, recuperación de workers muertos— sale de esa decisión.

**Stack:** Java 21 · Spring Boot 3.3 · Redis Streams · PostgreSQL · React + Vite · STOMP · Docker
Compose · Kubernetes (kind + KEDA).

---

## Estado actual

Funciona de punta a punta. 56 tests verdes (`mvn test`), todo dockerizado.

**Lo que está construido:**

- Cola sobre Redis Streams con consumer groups, at-least-once vía PEL, y recuperación con `XCLAIM`
- Prioridad por tier (dos streams), reintentos con backoff exponencial sobre un ZSET diferido, DLQ
- TTL por job con barrido periódico
- Pool de workers sobre virtual threads con shutdown ordenado
- API keys hasheadas, rate limiting por tier con costo proporcional
- Cancelación de jobs con guarda contra la carrera del worker
- Idempotencia por `Idempotency-Key` apoyada en índice único
- Submit en lote (hasta 100 jobs, una transacción)
- WebSocket autenticado en el CONNECT, con topic por API key
- Streaming de la respuesta del modelo token a token
- Inspección y reencolado de la DLQ desde `/admin`
- Retención por `XTRIM` con techo por stream
- Métricas Prometheus en `/actuator/prometheus`
- Dashboard React: jobs en vivo, cancelación, panel de DLQ, streaming
- Deploy a Kubernetes local: manifiestos, probes, Secrets, Ingress, y autoscaling de workers por
  backlog de la cola (KEDA sobre Redis Streams)

---

## Mapa del código

`backend/src/main/java/com/inferqueue/`

| Paquete | Qué vive ahí |
|---|---|
| `api` | Controllers, `JobService` (orquestación), `JobWriter` (inserts transaccionales), `DeadLetterService` |
| `domain` | Entidades JPA, repositorios, enums de estado, eventos (`JobEvent`, `JobTokenEvent`) |
| `queue` | `JobQueue` (wrapper de Streams), `DelayedQueue` (ZSET de retries), `StreamTrimmer`, `QueueMessage` |
| `worker` | `WorkerPool`, `JobExecutor`, `JobStateService`, `PendingReclaimer`, `TtlSweeper`, adapters de modelo |
| `security` | Filtro de API key, `ApiKeyService`, `RateLimiter`, `AdminTokenGuard` |
| `ws` | Config de STOMP, `StompAuthInterceptor`, bridge y relay de eventos por Redis pub/sub |
| `config` | `InferQueueProperties`, `WebConfig` |
| `metrics` | Contadores y timers de Micrometer |

**Migraciones:** `V1__init.sql` (schema base) · `V2__job_cancellation.sql` (estado `CANCELED`) ·
`V3__idempotency_key.sql` (índice único parcial).

**Dashboard:** `dashboard/src/` — `useJobStream.js` concentra WebSocket y polling; los componentes
son tontos.

**Kubernetes:** `k8s/` — `manifests/` (kustomize), `kind/cluster.yaml`, `loadgen/` (Job que llena la
cola para ver el escalado) y un `Makefile` que orquesta todo. El porqué de cada decisión está en
`k8s/README.md`; acá abajo sólo lo que es fácil romper.

---

## Invariantes: lo que no hay que romper

Esta es la sección importante. Cada una de estas reglas está sosteniendo un caso borde concreto, y
todas son fáciles de romper sin darse cuenta.

**1. Postgres es la fuente de verdad, Redis es transporte.** El mensaje del stream lleva sólo
`jobId`, prioridad, intento y timestamp — nunca el prompt ni el estado. Si alguna vez necesitás
meter más datos en el mensaje, casi seguro la solución correcta es otra.

**2. El `XADD` se difiere al commit.** Está en `JobWriter.afterCommit()`. Si encolás antes de
commitear, un worker rápido busca en la base un job que todavía no existe.

**3. El `XACK` va siempre al final,** después de dejar el job consistente en Postgres. Si el proceso
muere entre el resultado y el ack, el mensaje se reentrega y el chequeo de estado terminal lo
absorbe. Al revés perderías el job.

**4. Toda transición de estado pasa por `findByIdForUpdate` y aborta si el job ya está terminal.**
Está centralizado en `JobStateService.transition()`. Es lo que hace segura la cancelación: un worker
que termina su inferencia después de que el cliente canceló no puede pisar el `CANCELED` con un
`DONE`.

**Corolario, y es el que más fácil se olvida:** si una transición devuelve `Optional.empty()`, el
flujo **no debe continuar**. No programes el retry, no mandes a la DLQ, no cuentes la métrica —
sólo ackeá el mensaje y salí. Mirá `JobExecutor.handleFailure()`.

**5. El retry ackea el mensaje actual y encola uno nuevo.** Nunca dejes un mensaje esperando su
backoff dentro del PEL: bloquearía al reclaimer y lo haría reclamar algo que no está huérfano.

**6. La llamada al modelo nunca ocurre dentro de una transacción.** Por eso `JobStateService` está
separado de `JobExecutor`. Con 8 workers y una transacción abierta durante minutos, agotás el pool
de conexiones y se cae el gateway entero — la base muere por conexiones ocupadas, no por carga.

**7. La deduplicación por idempotencia se apoya en el índice único, no en un `SELECT` previo.** Por
eso el insert vive en `JobWriter`, un bean aparte: la violación de integridad recién aparece al
commitear, y para capturarla hay que cruzar el proxy transaccional.

**8. Todo destino STOMP se valida contra el `Principal` de la sesión.** Nunca publiques en un topic
global. Si agregás un tipo de evento nuevo, va bajo `/topic/keys/{apiKeyId}/...` y listo.

**9. `@Scheduled(fixedDelayString)` sólo acepta ISO-8601 (`PT15S`) o un número plano.** El formato
corto (`15s`) compila, bindea bien como `Duration` en las properties, y hace que el contexto de
Spring **no levante**. Ya rompió la app una vez (commit `6b18d50`).

**10. El Deployment del worker no lleva `replicas`.** El campo está omitido a propósito: la escala
la maneja el HPA que crea el ScaledObject de KEDA. Si alguien lo agrega, cada `kubectl apply`
devuelve el deployment a ese número y pisa al autoscaler.

**11. `terminationGracePeriodSeconds` tiene que superar el shutdown de Spring.** Hoy son 60s de pod
contra 40s de `spring.lifecycle.timeout-per-shutdown-phase` más 5s de `preStop`. Si se invierte, el
SIGKILL llega antes de que el worker termine los jobs en vuelo y esos mensajes quedan en el PEL
esperando el `XCLAIM`. Se recupera, pero se paga en latencia en cada scale-down.

**12. Los tests de integración comparten los streams entre sí.** No asumas una base limpia: aislá
por `jobId` y descartá lo que sea de otro test. Mirá el helper `deliverTo()` en
`QueueRecoveryIntegrationTest`.

---

## Deuda conocida y próximos pasos

Ordenado por lo que más duele primero.

### Alta

**El timeout de Ollama es config muerta.** `inferqueue.ollama.timeout` está declarada en
`InferQueueProperties` y seteada en `application.yml`, pero `OllamaAdapter` nunca la aplica al
`RestClient`. Si Ollama se cuelga, el worker espera para siempre y ese virtual thread no vuelve.
Sólo se manifiesta con backend real, por eso nunca apareció en los tests.

**`claim-idle-timeout` está calibrado para el `MockAdapter`.** Con 60s y un modelo real, una
inferencia lenta supera el timeout y `PendingReclaimer` le roba el mensaje a un worker que está
trabajando perfectamente — la misma inferencia corriendo dos veces en la GPU. Para inferencia real
hay que subirlo por encima del p99 (del orden de `PT10M`). Idealmente iría en un override de compose
para no ensuciar el default de las demos sin GPU.

**`/v1/stats` hace un `COUNT` por estado** (siete consultas) y el dashboard lo pide cada 3 segundos
por cada pestaña abierta. Con la tabla grande, eso pesa muchísimo más que encolar. La salida son
contadores incrementales en Redis o una tabla de agregados.

### Media

**Una sola instancia de Ollama para todos los workers.** `OLLAMA_URL` es global, así que no se puede
tener una instancia por GPU (pinneadas con `CUDA_VISIBLE_DEVICES`) con cada réplica apuntando a la
suya. Hoy Ollama reparte las capas de un modelo entre placas, que sirve para modelos grandes pero no
para throughput.

**El broker STOMP es en memoria.** El fan-out escala hasta donde escale una instancia del gateway.
Con varias réplicas, cada cliente sólo recibe los eventos que pasan por la suya — hoy funciona
porque el relay de Redis pub/sub llega a todas, pero el broker no está compartido.

**Rate limit de ventana fija:** admite hasta 2x el límite en el borde entre ventanas.

**Token de admin único y compartido** para todo `/admin`. Alcanza para operar, no para auditar quién
reencoló qué.

### Baja

- La cancelación no interrumpe la inferencia en curso: el worker la termina y descarta el resultado.
  Cortarla de verdad necesita que `ModelAdapter` exponga cancelación, y Ollama no la tiene.
- Los fragmentos de streaming no se persisten: si el dashboard se conecta a mitad de un job, ve el
  resultado recién al completarse.
- Bajo carga sostenida de prioridad, el stream standard se puede starvear. La salida sería reservar
  una fracción de los workers para standard.
- No hay CI. Los tests de integración necesitan Docker, así que el runner tiene que tener socket.
- En el cluster faltan NetworkPolicies (hoy cualquier pod alcanza el `:5432`), TLS, y un Prometheus
  que scrapee el `/actuator/prometheus` que ya está expuesto.

### Deploy: fases siguientes

La Fase 1 (Kubernetes local con kind) está hecha y es donde vive el trabajo conceptual: los
manifiestos son los mismos que correrían en producción. Lo que falta es plata y una URL pública.

**Fase 2 — AWS con Terraform.** ECS Fargate + RDS PostgreSQL + ElastiCache, toda la infra como
código, casi gratis con free tier. Lo que aporta que la Fase 1 no puede: una demo viva y pública.
Los manifiestos de `k8s/` no se tiran — las mismas imágenes, las mismas variables de entorno, el
mismo arranque ordenado, sólo cambia quién lo agenda. Postgres y Redis dejan de ser StatefulSets y
pasan a ser servicios gestionados, que es como debería ser.

**Fase 3 — EKS, opcional.** Sólo por el combo completo. El control plane cuesta ~73 USD/mes, así
que es levantarlo un sábado, sacar capturas y `terraform destroy` el domingo.

### Ideas de features

Webhooks de callback al completarse (para clientes que no quieren mantener un WebSocket), quotas
mensuales por key además del rate limit, spec OpenAPI generada, reencolado masivo desde la DLQ,
routing de modelo por capacidad del worker.

---

## Cómo correrlo

```bash
docker compose up --build                    # todo con MockAdapter, sin GPU
MODEL_ADAPTER=ollama docker compose up       # inferencia real contra Ollama en el host
```

Dashboard en `:5173`, API en `:8080`. Para usar GPU hace falta Ollama corriendo con
`OLLAMA_HOST=0.0.0.0` (si no, los contenedores no lo alcanzan) y los modelos ya bajados con
`ollama pull`.

```bash
cd backend && mvn test        # necesita Docker: los de integración levantan Postgres y Redis
cd dashboard && npm run build
```

En Kubernetes local:

```bash
cd k8s && make up        # kind + ingress + metrics-server + KEDA + build + deploy
make loadgen             # 300 jobs encolados, para ver mover el autoscaler
make watch               # HPA, ScaledObject y pods
make down                # borra el cluster
```

**Nota sobre Testcontainers:** está pinneado a 1.21.3 porque la versión que fija Boot 3.3 no arranca
contra Docker Engine moderno. Además, `DockerApiVersion` detecta con `docker version` qué API habla
el demonio y se alinea, porque el cliente negocia 1.41 por defecto y Engine 29 ya no la acepta. Si
hace falta forzar un valor, respeta `-Dapi.version` y `DOCKER_API_VERSION`.

---

## Convenciones

- **Commits en español,** con prefijo y scope (`feat(queue):`, `fix:`, `test:`, `docs:`). El cuerpo
  explica el *porqué* y el trade-off aceptado, no el qué — eso ya está en el diff.
- **Los comentarios explican decisiones, no mecánica.** Si el comentario se puede deducir leyendo la
  línea de abajo, sobra. Si explica por qué se eligió esto y no lo obvio, va.
- **Los tests apuntan a modos de falla reales,** no a cobertura. Cada uno tiene un `@DisplayName` en
  español que describe el escenario, no el método.
- **Los nombres de dominio en inglés** (`Job`, `DeadLetterEntry`), la prosa en español.
