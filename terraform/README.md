# InferQueue en AWS (Fase 2)

El mismo sistema de la Fase 1, con la infraestructura como código y una URL pública.
Postgres y Redis dejan de ser StatefulSets que uno mantiene y pasan a ser RDS y
ElastiCache. Las imágenes son exactamente las mismas: no hay una sola línea de Java
distinta entre correr esto en `docker compose`, en kind o acá.

```bash
cd terraform
make up          # repos de ECR + push de imágenes + toda la infra
make url
```

| | |
|---|---|
| Dashboard | la URL del ALB |
| API | `<url>/v1/jobs` |
| Admin | `<url>/admin/...` con `X-Admin-Token: $(make token)` |

```bash
make status      # cuántas tareas corren de cada servicio
make loadgen     # encola 300 jobs contra la demo
make watch       # la métrica de cola, las alarmas y el desired count del worker
make logs        # logs de los workers
make exec        # una shell dentro del gateway (el kubectl exec de ECS)
make deploy      # rebuild + push + redeploy, sin tocar infra
make down        # destruye todo
```

Requisitos: `terraform` ≥ 1.6, `aws` con credenciales configuradas, `docker`, `make`.
Para `make exec`, además, el `session-manager-plugin`.

## Lo que cuesta, antes que nada

Esto **no es casi gratis**, y conviene saberlo antes y no en la factura. El free tier
cubre la base y el Redis por 12 meses; no cubre lo que más pesa, que es el balanceador
y Fargate.

| | USD/mes | Free tier |
|---|---|---|
| ALB | ~16 + LCU | no |
| Gateway (0.5 vCPU / 1 GB, on-demand) | ~18 | no |
| Worker (0.5 vCPU / 1 GB, Spot, 1 tarea) | ~6 | no |
| Dashboard (0.25 vCPU / 0.5 GB, Spot) | ~3 | no |
| Endpoint de interfaz de CloudWatch | ~7 | no |
| RDS `db.t4g.micro` + 20 GB | ~14 | sí, 12 meses |
| ElastiCache `cache.t4g.micro` | ~12 | sí, 12 meses |
| CloudWatch (4 métricas, 2 alarmas) | ~1.4 | parcial |
| Lambda (43 mil invocaciones) | 0 | sí |

Redondeando: **~0.07 USD/hora** con el free tier vigente, **~0.10 USD/hora** sin él.
Unos 50 a 73 USD si queda prendido el mes entero.

De ahí sale la forma de usarlo, que es la misma que la Fase 3 propone para EKS:
`make up` el sábado, sacar las capturas y el video, `make down` el domingo. Un fin de
semana entero cuesta menos de 5 USD. Por eso `terraform destroy` tiene que terminar
sin intervención, y por eso `skip_final_snapshot`, `deletion_protection = false` y
`force_delete` en los repos de ECR están donde están: en producción los tres van al
revés, y ese contraste es justamente la diferencia entre un entorno y otro, no entre
un código y otro.

El otro número a mirar es `worker_max_count`. Es el techo del escalado y por lo tanto
el techo del gasto: seis tareas en Spot durante un pico son unos 0.05 USD/hora.

## Qué cambia respecto de la Fase 1

Casi todo tiene una traducción directa. Vale la pena leerla como tabla porque deja
claro qué era esencial del diseño y qué era vocabulario de Kubernetes.

| Fase 1 (kind) | Fase 2 (AWS) |
|---|---|
| Deployment | Servicio de ECS + task definition |
| StatefulSet de Postgres / Redis | RDS + ElastiCache |
| Service | ENI de la tarea, registrada en un target group |
| Ingress nginx | ALB con reglas de listener |
| Secret + `secretKeyRef` | Parameter Store SecureString + bloque `secrets` |
| ConfigMap | `environment` de la task definition |
| initContainer | contenedor no esencial + `dependsOn: SUCCESS` |
| readiness probe | health check del target group |
| liveness probe | *(no hay equivalente, ver abajo)* |
| `preStop: sleep 5` | `deregistration_delay` |
| `terminationGracePeriodSeconds` | `stopTimeout` |
| HPA de CPU | target tracking sobre `ECSServiceAverageCPUUtilization` |
| ScaledObject de KEDA | Lambda + métrica + alarma + step scaling |
| `kubectl exec` | `aws ecs execute-command` |
| `kind load docker-image` | `docker push` a ECR |

Lo que no tiene traducción es la probe de liveness. El health check del target group
cubre al gateway —si deja de contestar 200, el ALB lo saca y ECS lo reemplaza— pero el
worker no está detrás de ningún balanceador, así que un worker vivo pero colgado se
queda colgado. En Kubernetes eso lo resolvía la liveness probe. Acá haría falta un
`healthCheck` a nivel de contenedor, y la imagen base (`eclipse-temurin:21-jre`) no
trae `curl` ni `wget` con los que consultarse a sí misma. Está anotado como deuda, no
resuelto.

## Autoscaling: la parte que no se traduce sola

Es la pieza central de las dos fases y es donde más cambian las cosas.

En la Fase 1 el trabajo lo hacía KEDA: el operador hablaba Redis directamente, hacía
`XLEN`, dividía por el objetivo y le entregaba el número a un HPA. Una pieza, un
manifiesto de treinta líneas.

En ECS no existe nada que sepa leer Redis. La cadena queda en cuatro:

```
Lambda (cada minuto)  →  métrica en CloudWatch  →  alarma  →  step scaling policy
```

**La métrica sigue siendo `XLEN`,** y por el mismo motivo exacto que en la Fase 1: el
worker borra cada mensaje del stream después de ackearlo (`JobQueue.delete`), así que
lo que queda adentro es precisamente el trabajo pendiente más el que está en vuelo.
Los otros dos números que uno miraría no sirven acá y la trampa es idéntica: el `lag`
de `XINFO GROUPS` queda en NULL en cuanto se borran entradas —se leería 0 con la cola
llena— y el largo del PEL sube cuando los workers están *ocupados*, que es escalar al
revés. Esto está escrito otra vez en `lambda/queue_depth.py` porque es el tipo de cosa
que se rompe cuando alguien "mejora" la métrica sin conocer la historia.

**El cliente de Redis se escribe a mano.** Son veinte líneas de socket: `XLEN` es un
comando de dos argumentos y una respuesta entera. La alternativa era empaquetar
`redis-py` en un layer y versionarlo, para usar el 1% de la biblioteca.

Dos diferencias con KEDA que se notan al mirar la demo:

**La cadencia es peor.** KEDA consultaba cada 10 segundos. EventBridge no baja de 1
minuto y la alarma necesita al menos un período de 60 segundos, así que entre que la
cola crece y aparece la primera tarea nueva pasan 1 o 2 minutos, contra los ~15
segundos de la Fase 1. Para jobs de inferencia que tardan minutos es tolerable; para
una cola de latencia baja no lo sería, y ahí la respuesta sería otra arquitectura, no
un ajuste de este parámetro.

**Es step scaling y no target tracking.** El HPA hacía target tracking: mantené
backlog/réplicas en 10. Para hacer lo mismo en ECS hace falta una métrica *por tarea*,
y para calcularla hay que saber cuántas tareas corren, lo que implica o activar
Container Insights (que se cobra por métrica) o darle a la Lambda permiso sobre la API
de ECS más otro endpoint de interfaz para poder llamarla — otros 7 USD/mes por un
divisor. Step scaling sobre el backlog total evita las dos cosas: de 20 jobs para
arriba suma 2 tareas, de 120 para arriba suma 4, y por debajo de 5 sostenido devuelve
de a una. Lo que se pierde es la convergencia suave; lo que se gana es que el
autoscaler no cuesta nada.

**El piso es 1, nunca 0.** Mismo motivo que el `minReplicaCount` de KEDA: el
`PendingReclaimer` y el `TtlSweeper` viven dentro del worker. Sin ninguna tarea
corriendo, un job huérfano en el PEL no lo reclama nadie y uno vencido no lo barre
nadie.

## Los workers van en Spot, y no es una concesión

Fargate Spot cuesta ~70% menos y AWS puede matar la tarea con 2 minutos de aviso. Para
la mayoría de las aplicaciones eso es un problema que hay que mitigar. Acá no es un
riesgo nuevo: perder un consumidor a mitad de un job es exactamente el caso que el PEL
y el `XCLAIM` del `PendingReclaimer` resuelven desde el primer día. La interrupción
manda `SIGTERM`, el `stopTimeout` de 60s le da al worker tiempo de terminar lo que
tiene en vuelo, y lo que no llegue a ackear lo reclama otro worker al cabo de
`claim-idle-timeout`.

El gateway, en cambio, va on-demand: sostiene las sesiones WebSocket del dashboard y
una interrupción se ve como una desconexión en la cara del que está mirando la demo.

## Arranque y apagado

El arranque ordenado es el mismo problema que en la Fase 1 y se resuelve igual de
parecido: un contenedor no esencial que corre primero y del que el worker depende con
`dependsOn: SUCCESS`. Lo único que cambia es qué espera. En kind esperaba el readiness
del gateway (que sólo se declara listo después de migrar); acá consulta directamente
la tabla `flyway_schema_history` con `psql`. Es más directo —pregunta por lo que
realmente hace falta— y no depende de que haya DNS entre servicios, que en ECS sin
Service Connect no existe.

El apagado, en cambio, ECS lo hace **mejor** que Kubernetes, y es de las pocas veces
que se puede decir eso. Kubernetes manda el `SIGTERM` y borra el endpoint en paralelo,
no en orden, y por eso la Fase 1 necesita un `preStop: sleep 5` para no comerse 502 en
cada deploy. ECS lo hace secuencial: desregistra del target group, espera el
`deregistration_delay`, y recién entonces manda el `SIGTERM`. El sleep no hace falta.

Lo que sí se mantiene es el invariante: el `stopTimeout` (60s) tiene que superar el
`spring.lifecycle.timeout-per-shutdown-phase` (40s). Si se invierte, el `SIGKILL` llega
antes de que el worker termine los jobs en vuelo y esos mensajes quedan esperando el
`XCLAIM`. En Fargate el techo de `stopTimeout` es 120s, así que hay margen pero no
infinito.

## Red: por qué no hay NAT Gateway

La decisión que define el resto del diseño de red. El libro dice tareas en subredes
privadas saliendo por un NAT Gateway. Un NAT cuesta ~32 USD/mes más el tráfico: sería,
por lejos, el ítem más caro del stack — más que la base, el Redis y el ALB juntos. Así
que las tareas de Fargate van en subredes públicas con IP pública y salen por el
Internet Gateway, que es gratis.

Lo que se pierde es menos de lo que suena. La IP pública no abre nada: quien decide qué
entra es el security group, y el de las tareas sólo acepta tráfico del security group
del ALB. Lo que sí se pierde es defensa en profundidad — una regla mal escrita expone
la tarea a internet, contra un NAT donde directamente no hay ruta de entrada posible.

Por eso los datos no juegan a esto. RDS y ElastiCache viven en subredes privadas sin
ruta a internet y no hay forma de alcanzarlos desde afuera.

El caso que el truco no cubre: **una Lambda en una VPC nunca recibe IP pública**, así
que el publicador de métricas no puede salir por el Internet Gateway. Necesita llamar
a `PutMetricData` y para eso hay un endpoint de interfaz, que mete la API de CloudWatch
adentro de la VPC como una ENI privada. Cuesta ~7 USD/mes, cuatro veces menos que el
NAT que evita. (Los logs de la Lambda no pasan por ahí: esa entrega la hace el servicio
de Lambda por fuera de la ENI del cliente.)

## Tres trampas que ya costaron tiempo

**El `maxmemory-policy` de ElastiCache viene en `volatile-lru`.** O sea: cuando la
memoria se llena, Redis empieza a desalojar claves con TTL. En un cache eso es lo
correcto. Acá adentro no hay un cache — hay una cola, un PEL con los jobs en vuelo y
un ZSET de reintentos — y desalojar cualquiera de los tres es perder trabajo en
silencio, sin un error en ningún lado. Por eso hay un parameter group propio cuya única
razón de existir es poner `noeviction`, que hace que Redis conteste con error en vez de
tirar datos.

**El nginx del dashboard no arranca si `gateway` no resuelve.** La imagen tiene
`proxy_pass http://gateway:8080` para el modo compose, y nginx resuelve el nombre del
upstream al cargar la configuración, no al primer request: si no resuelve, no levanta
—`host not found in upstream`— y la tarea muere en loop. En kind el nombre existe
porque hay un Service; en ECS no hay DNS entre servicios. La task definition lo apunta
a `127.0.0.1` con `extraHosts`. La ruta queda muerta, pero es la misma ruta muerta que
en la Fase 1: el ALB manda `/v1`, `/admin` y `/ws` al gateway antes de que lleguen ahí.

**Los permisos de secretos van en el rol de ejecución, no en el de tarea.** El rol de
ejecución lo usa el agente de ECS *antes* de que el contenedor exista: bajar la imagen,
resolver los `secrets`, abrir el log group. El rol de tarea lo usa la aplicación ya
corriendo. Ponerlos en el segundo es el error clásico, y el síntoma no ayuda: la tarea
muere en `PROVISIONING` con un `AccessDeniedException` y sin un solo log de la
aplicación. Un detalle más: un SecureString está cifrado con la clave `aws/ssm`, así
que además del permiso de SSM hace falta `kms:Decrypt`.

## Diagnóstico

El orden que sirve, de afuera hacia adentro:

```bash
make status                             # ¿cuántas tareas corren vs. cuántas se piden?
aws ecs describe-services --cluster inferqueue --services gateway \
  --query 'services[0].events[:10]'     # el "describe" de ECS: por qué no arranca
make logs                               # logs de la aplicación
```

Si `runningCount` se queda en 0 con `desiredCount` en 1, el problema es anterior a la
aplicación —imagen, secretos, red— y está en los eventos del servicio, no en los logs.
Si las tareas corren pero el ALB devuelve 503, el target group las tiene unhealthy:

```bash
aws elbv2 describe-target-health --target-group-arn $(aws elbv2 describe-target-groups \
  --names inferqueue-gateway --query 'TargetGroups[0].TargetGroupArn' --output text)
```

Y si el autoscaler no se mueve, la cadena tiene cuatro eslabones y conviene recorrerla
en orden en vez de mirar el final:

```bash
aws logs tail /aws/lambda/inferqueue-queue-depth --since 10m   # 1. ¿publica?
make watch                                                     # 2. ¿la métrica llega, y la alarma cambia?
aws application-autoscaling describe-scaling-activities \
  --service-namespace ecs --resource-id service/inferqueue/worker   # 3. ¿la política actuó?
```

Es la misma lección que dejó la Fase 1: si el primer eslabón falla, el último no tiene
nada que mostrar y mirarlo no informa nada.

## Qué no está acá

- **HTTPS**, salvo que se pase `certificate_arn`. Sin dominio propio el ALB queda en
  HTTP plano. El dashboard funciona igual porque deriva `ws://` o `wss://` de
  `window.location`, pero una demo pública debería tener TLS.
- **Alta disponibilidad.** RDS single-AZ, un nodo de ElastiCache, sin réplica. Duplicar
  cada uno duplica la factura y esto es una demo.
- **Persistencia de Redis.** Sin AOF como el `--appendonly yes` del compose: si el nodo
  se reinicia, se pierde el contenido de los streams. Los jobs siguen en Postgres, pero
  los que estaban `RUNNING` quedan sin mensaje en el PEL y hay que reencolarlos a mano.
- **WAF, CloudFront, Route53.** El dashboard es estático y viviría mejor en S3 detrás
  de CloudFront que en una tarea de Fargate; se dejó como contenedor para que el mapeo
  con la Fase 1 sea uno a uno.
- **Pipeline de CI/CD.** `make deploy` construye y empuja desde la máquina de uno.
- **Backend remoto de estado.** El `terraform.tfstate` es local. Está el bloque de S3
  comentado en `versions.tf`; la contra de no usarlo es concreta: si se pierde el
  archivo, `terraform destroy` ya no sabe qué destruir.
