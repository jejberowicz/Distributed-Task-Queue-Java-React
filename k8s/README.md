# InferQueue en Kubernetes (local, con kind)

El mismo sistema que levanta `docker compose`, pero descrito como lo describirías en
producción: Deployments y StatefulSets, probes, Secrets, Ingress y autoscaling real.
Corre entero en la máquina y no cuesta nada.

## Levantarlo

```bash
cd k8s
make up          # cluster + ingress + metrics-server + KEDA + imágenes + deploy
```

Después de eso:

| | |
|---|---|
| Dashboard | http://localhost/ |
| API | `http://localhost/v1/jobs` |
| Admin | `http://localhost/admin/...` con `X-Admin-Token: dev-admin-token` |

```bash
make status      # todo lo que quedó corriendo
make loadgen     # encola 300 jobs para mover el autoscaler
make watch       # HPA, ScaledObject y pods, refrescando
make logs        # logs de todos los workers juntos
make down        # borra el cluster entero
```

Requisitos: `docker`, `kind`, `kubectl`, y `make`.

## Qué demuestra cada pieza

### Autoscaling por largo de la cola, no por CPU

Es la decisión central y está en `manifests/40-worker-scaledobject.yaml`.

Un worker de InferQueue pasa la mayor parte de su vida bloqueado esperando la
respuesta del modelo. En CPU está ocioso. Un HPA por utilización miraría eso, vería
pods relajados con la cola creciendo, y escalaría hacia abajo — exactamente al revés
de lo que hace falta. La señal correcta es el backlog: cuántas entradas del stream
todavía no consumió el consumer group.

KEDA expone ese número como external metric y crea el HPA por debajo. El HPA es real
y se ve con `kubectl get hpa -n inferqueue`; KEDA sólo aporta de dónde sale la métrica.

Dos detalles que importan más de lo que parecen:

- Se usa **`lagCount`**, no `pendingEntriesCount`. El primero mide el backlog real; el
  segundo mide el PEL, o sea mensajes ya entregados y sin ackear, que es un número que
  sube justo cuando los workers están *ocupados*. Escalar por él confunde "hay trabajo
  esperando" con "el trabajo está tardando".
- **`minReplicaCount: 1`**, no cero. Con cero réplicas nadie corre el `PendingReclaimer`,
  y los mensajes huérfanos de un worker muerto se quedarían en el PEL sin que nadie los
  reclame con `XCLAIM`. Además `lagCount` necesita que el consumer group exista, y quien
  lo crea es el worker al arrancar.

El gateway, en cambio, sí escala por CPU (`41-gateway-hpa.yaml`): su trabajo es
síncrono —validar, insertar, `XADD`— y ahí la utilización sí correlaciona con la carga.
Que las dos mitades escalen por señales distintas es el punto: son cuellos de botella
distintos.

Para verlo:

```bash
make loadgen &        # 300 jobs
make watch            # worker pasa de 1 a ~10, y vuelve a bajar de a uno
```

### Probes, y por qué son tres

En `20-gateway.yaml`. Cada una responde una pregunta distinta y confundirlas es el
error clásico:

- **startup** — le da tiempo a la JVM a arrancar sin que liveness la mate a mitad de camino.
- **readiness** — saca al pod del Service cuando no puede atender, sin reiniciarlo.
- **liveness** — lo reinicia, y sólo si está colgado de verdad.

Apuntan a `/actuator/health/readiness` y `/actuator/health/liveness`, que se habilitaron
con `management.endpoint.health.probes.enabled` en `application.yml`.

### Arranque ordenado sin `depends_on`

Kubernetes no tiene el `depends_on` de compose, y está bien: la respuesta correcta es
que cada pod sepa esperar lo que necesita.

- El gateway espera a que Postgres acepte conexiones (initContainer). Spring no reintenta
  la conexión inicial, así que sin esto el pod entra en CrashLoopBackOff. Se recupera
  solo, pero el backoff retrasa todo el arranque.
- Los workers esperan la **readiness del gateway**, no la de Postgres. Corren con
  `ddl-auto: validate` y sin Flyway: necesitan el schema ya migrado, y el gateway —que es
  quien migra— recién se declara ready cuando terminó. Esperar su readiness es esperar el schema.

Con dos réplicas de gateway, Flyway arranca en las dos a la vez; el lock de la tabla de
historial serializa las migraciones y la segunda no hace nada.

### Apagado ordenado

Un worker que recibe SIGTERM deja de leer del stream y termina los jobs en vuelo. Para
que eso funcione hicieron falta tres cosas alineadas:

1. `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 40s`.
2. `terminationGracePeriodSeconds: 60` en el pod, con margen sobre ese timeout.
3. Que el proceso Java sea PID 1 y reciba la señal — ya venía del `ENTRYPOINT` del Dockerfile.

Si el SIGKILL llega antes, los mensajes quedan en el PEL y hay que esperar el `XCLAIM`
del reclaimer. Se recupera, pero se paga en latencia en cada scale-down.

El gateway además tiene un `preStop` de 5 segundos: Kubernetes manda el SIGTERM y borra
el endpoint **en paralelo**, no en orden, así que sin esa pausa el kube-proxy sigue
mandando requests unos milisegundos a un pod que ya está cerrando — 502 en cada deploy.

### Secrets y config, separados

`01-secret.yaml` tiene la contraseña de Postgres y el token de admin; `02-configmap.yaml`
el resto. La separación no es cosmética: tienen ciclos de vida distintos y el ConfigMap
se puede leer en un `kubectl get -o yaml` sin exponer nada. Ningún Deployment repite un
valor sensible: todos van por `secretKeyRef`.

El Secret está versionado a propósito para que el cluster levante con un solo `apply`.
En cualquier cosa que no sea local esto sale del repo — Sealed Secrets, External Secrets
Operator, o el store del proveedor.

### Ingress

`30-ingress.yaml` rutea `/v1`, `/admin` y `/ws` al gateway, y todo lo demás al dashboard.
El `extraPortMappings` del cluster de kind mapea el `:80` del host al ingress, que es lo
que hace que `http://localhost` funcione sin port-forward.

Dos cosas explícitas ahí:

- Los timeouts de proxy a 3600s. Una conexión STOMP ociosa espera eventos de jobs que
  pueden tardar minutos; con el default de 60s el ingress la corta y el dashboard
  reconecta en loop.
- **`/actuator` no se expone.** No tiene auth —el filtro de API key sólo cubre `/v1`— y
  publicaría métricas y detalle de health. Se scrapea desde adentro del cluster o con
  `kubectl port-forward`.

## Diferencias con `docker compose`

| | compose | Kubernetes |
|---|---|---|
| Orden de arranque | `depends_on` + healthcheck | initContainers que esperan lo suyo |
| Escalar workers | `--scale worker=N` a mano | ScaledObject por backlog de la cola |
| Entrada | puertos publicados | Ingress en `:80` |
| Config sensible | variables de entorno | Secret + `secretKeyRef` |
| Inferencia real | `host.docker.internal` al Ollama del host | hace falta un Service alcanzable desde el cluster |

El dashboard usa la **misma imagen** en los dos casos. Su nginx interno sigue sabiendo
proxear `/v1` hacia `gateway:8080` porque lo necesita en compose; en Kubernetes ese salto
no se usa, el ingress va directo al gateway. Un efecto de eso: nginx resuelve `gateway`
al arrancar, así que si el Service todavía no existe el pod del dashboard falla y
reintenta. `kubectl apply -k` los crea juntos, pero si ves un dashboard en
CrashLoopBackOff los primeros segundos, es esto.

## Qué no está acá

- **TLS.** En local no aporta nada; en la nube va con cert-manager y un Issuer de Let's Encrypt.
- **NetworkPolicies.** Hoy cualquier pod puede hablar con Postgres. Lo correcto es que
  sólo gateway y worker lleguen al `:5432`.
- **Postgres y Redis como StatefulSet propios.** Sirve para la demo. En producción son
  servicios gestionados — que es exactamente lo que hace la Fase 2 con RDS y ElastiCache.
- **Observabilidad.** Las métricas Prometheus ya están en `/actuator/prometheus`; falta el
  Prometheus que las scrapee y un Grafana con el backlog y la latencia p99.
