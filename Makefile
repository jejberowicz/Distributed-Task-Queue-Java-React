# InferQueue en local con Docker Compose.
#
# `make up` deja todo corriendo; `make apikey` emite una key y `make job KEY=iq_…`
# encola un job con ella. Para kind o AWS están k8s/Makefile y terraform/Makefile.

ADMIN_TOKEN ?= dev-admin-token
API         ?= http://localhost:8080
WORKERS     ?= 2
PROMPT      ?= explicá XCLAIM

.PHONY: up down clean ps logs scale apikey job dlq loadgen urls

# --wait vuelve recién cuando los healthchecks pasan: al terminar, la API ya responde.
up:
	docker compose up -d --build --wait
	@$(MAKE) --no-print-directory urls

down:
	docker compose down

# Además borra los volúmenes: Postgres y Redis arrancan vacíos la próxima vez.
clean:
	docker compose down -v

ps:
	docker compose ps

logs:
	docker compose logs -f --tail=50 gateway worker

scale:
	docker compose up -d --no-recreate --scale worker=$(WORKERS)

apikey:
	@curl -sf -X POST $(API)/admin/api-keys \
		-H 'X-Admin-Token: $(ADMIN_TOKEN)' -H 'Content-Type: application/json' \
		-d '{"name":"cli","tier":"PREMIUM"}'; echo

job:
	@test -n "$(KEY)" || { echo "falta la key: make job KEY=iq_…  (sacala con make apikey)"; exit 1; }
	@curl -sf -X POST $(API)/v1/jobs \
		-H 'Authorization: Bearer $(KEY)' -H 'Content-Type: application/json' \
		-H "Idempotency-Key: make-$$(date +%s%N)" \
		-d '{"model":"llama3","type":"COMPLETION","prompt":"$(PROMPT)","ttlSeconds":300}'; echo

# Carga de prueba con la key del dashboard, para ver los jobs en su tabla.
# JOBS, FAILS, MODEL y DELAY se pasan igual: make loadgen KEY=iq_… JOBS=50
loadgen:
	@KEY="$(KEY)" sh scripts/loadgen.sh

dlq:
	@curl -sf $(API)/admin/dlq -H 'X-Admin-Token: $(ADMIN_TOKEN)'; echo

urls:
	@echo
	@echo "  dashboard   http://localhost:5173"
	@echo "  api         $(API)"
	@echo
	@echo "  make apikey   →   make job KEY=iq_…   ·   make loadgen KEY=iq_…   ·   make down"
	@echo
