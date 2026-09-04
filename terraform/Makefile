# Fase 2: InferQueue en AWS.
#
# `make up` deja la demo pública en una URL. `make down` la borra entera —y eso
# es tan parte del flujo como levantarla, porque acá el reloj corre.

TF       := terraform
PROJECT  ?= inferqueue
REGION   ?= us-east-1
ROOT     := $(shell git rev-parse --show-toplevel)
TAG      ?= latest

# Se calcula en vez de leerse de un output: los repos de ECR tienen que existir
# antes del apply completo, y en ese punto todavía no hay outputs que leer.
ACCOUNT   = $(shell aws sts get-caller-identity --query Account --output text)
REGISTRY  = $(ACCOUNT).dkr.ecr.$(REGION).amazonaws.com
BACKEND   = $(REGISTRY)/$(PROJECT)/backend
DASHBOARD = $(REGISTRY)/$(PROJECT)/dashboard

CLUSTER = $(PROJECT)

.PHONY: up down init ecr login push apply plan deploy url token status watch logs exec loadgen destroy

# El orden importa y es circular si uno no lo rompe: las tareas no arrancan sin
# imagen, y no hay dónde empujar la imagen sin los repos. Así que primero los
# repos solos, después el push, y recién ahí todo lo demás.
up: init ecr push apply url

init:
	$(TF) init -input=false

ecr:
	$(TF) apply -input=false -auto-approve \
		-target=aws_ecr_repository.backend \
		-target=aws_ecr_repository.dashboard

login:
	aws ecr get-login-password --region $(REGION) \
		| docker login --username AWS --password-stdin $(REGISTRY)

# --platform explícito: en una Mac con Apple Silicon el build por defecto sale
# arm64 y Fargate lo rechaza con "exec format error", que no menciona la
# arquitectura por ningún lado.
push: login
	docker build --platform linux/amd64 -t $(BACKEND):$(TAG) $(ROOT)/backend
	docker build --platform linux/amd64 -t $(DASHBOARD):$(TAG) $(ROOT)/dashboard
	docker push $(BACKEND):$(TAG)
	docker push $(DASHBOARD):$(TAG)

plan:
	$(TF) plan -input=false

apply:
	$(TF) apply -input=false -auto-approve

# Reconstruir e ir a producción sin tocar la infra. La task definition no cambia
# —apunta a :latest— así que hay que forzar el redeploy a mano.
deploy: push
	aws ecs update-service --cluster $(CLUSTER) --service gateway   --force-new-deployment --region $(REGION) >/dev/null
	aws ecs update-service --cluster $(CLUSTER) --service worker    --force-new-deployment --region $(REGION) >/dev/null
	aws ecs update-service --cluster $(CLUSTER) --service dashboard --force-new-deployment --region $(REGION) >/dev/null
	@echo "redeploy disparado en los tres servicios"

url:
	@echo
	@echo "  dashboard   $$($(TF) output -raw url)/"
	@echo "  api         $$($(TF) output -raw url)/v1/jobs"
	@echo "  admin       $$($(TF) output -raw url)/admin/api-keys   (X-Admin-Token: make token)"
	@echo
	@echo "  carga:      make loadgen        escalado: make watch"
	@echo

token:
	@$(TF) output -raw admin_token; echo

status:
	@aws ecs describe-services --cluster $(CLUSTER) --region $(REGION) \
		--services gateway worker dashboard \
		--query 'services[].{servicio:serviceName,deseadas:desiredCount,corriendo:runningCount,estado:status}' \
		--output table

# El equivalente de `kubectl get hpa -w`: lo que hay que mirar es la métrica que
# publica la Lambda y el estado de las dos alarmas que la leen.
watch:
	watch -n 15 ' \
		aws cloudwatch get-metric-statistics --namespace InferQueue --metric-name QueueDepth \
			--dimensions Name=Project,Value=$(PROJECT) --statistics Average \
			--start-time $$(date -u -d "-10 minutes" +%FT%TZ) --end-time $$(date -u +%FT%TZ) \
			--period 60 --region $(REGION) \
			--query "sort_by(Datapoints,&Timestamp)[-5:].{hora:Timestamp,cola:Average}" --output table; \
		aws cloudwatch describe-alarms --region $(REGION) \
			--alarm-names $(PROJECT)-queue-deep $(PROJECT)-queue-drained \
			--query "MetricAlarms[].{alarma:AlarmName,estado:StateValue}" --output table; \
		aws ecs describe-services --cluster $(CLUSTER) --region $(REGION) --services worker \
			--query "services[].{deseadas:desiredCount,corriendo:runningCount}" --output table'

logs:
	aws logs tail /ecs/$(PROJECT)/worker --follow --region $(REGION)

# El `kubectl exec` de ECS. Pide la session-manager-plugin instalada.
exec:
	@TASK=$$(aws ecs list-tasks --cluster $(CLUSTER) --service-name gateway --region $(REGION) \
		--query 'taskArns[0]' --output text); \
	aws ecs execute-command --cluster $(CLUSTER) --task $$TASK --container gateway \
		--interactive --command /bin/bash --region $(REGION)

loadgen:
	API=$$($(TF) output -raw url) ADMIN_TOKEN=$$($(TF) output -raw admin_token) \
		sh scripts/loadgen.sh

# Nada de esto está en el free tier de forma indefinida, y el ALB corre aunque
# no lo mire nadie. Destruir cuando se termina de mostrar es parte del diseño.
down: destroy

destroy:
	$(TF) destroy -input=false
