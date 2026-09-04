# Fase 1: InferQueue sobre Kubernetes local (kind).
#
# `make up` deja el sistema corriendo en http://localhost. Todo lo de acá es
# idempotente: se puede repetir sin romper nada.

CLUSTER            ?= inferqueue
NS                 ?= inferqueue
ROOT               := $(shell git rev-parse --show-toplevel)
BACKEND_IMAGE      ?= inferqueue/backend:dev
DASHBOARD_IMAGE    ?= inferqueue/dashboard:dev

# Versiones pinneadas a propósito: un `latest` acá convierte un cluster que
# funcionaba ayer en un debug de media hora.
KEDA_VERSION       ?= v2.16.1
INGRESS_VERSION    ?= controller-v1.11.3
METRICS_VERSION    ?= v0.7.2

.PHONY: up down cluster ingress metrics keda images deploy loadgen status watch logs urls clean-jobs

up: cluster ingress metrics keda images deploy urls

cluster:
	@kind get clusters 2>/dev/null | grep -qx "$(CLUSTER)" \
		|| kind create cluster --config kind/cluster.yaml
	@kubectl config use-context kind-$(CLUSTER)

ingress:
	kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/$(INGRESS_VERSION)/deploy/static/provider/kind/deploy.yaml
	kubectl -n ingress-nginx wait --for=condition=ready pod \
		--selector=app.kubernetes.io/component=controller --timeout=180s

# El HPA del gateway mide utilización de CPU, y sin metrics-server esa métrica no
# existe. En kind hay que desactivarle la verificación del certificado del kubelet,
# que está firmado para un hostname que no resuelve dentro del cluster.
metrics:
	kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/$(METRICS_VERSION)/components.yaml
	kubectl -n kube-system patch deployment metrics-server --type=json \
		-p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' || true
	kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s

keda:
	kubectl apply --server-side -f https://github.com/kedacore/keda/releases/download/$(KEDA_VERSION)/keda-$(KEDA_VERSION:v%=%).yaml
	kubectl -n keda rollout status deployment/keda-operator --timeout=180s

# kind corre en su propio contenedor con su propio containerd: las imágenes del
# Docker del host no las ve. `kind load` es lo que las mete adentro, y por eso los
# Deployments van con imagePullPolicy: IfNotPresent — no hay registry del que bajarlas.
images:
	docker build -t $(BACKEND_IMAGE) $(ROOT)/backend
	docker build -t $(DASHBOARD_IMAGE) $(ROOT)/dashboard
	kind load docker-image --name $(CLUSTER) $(BACKEND_IMAGE) $(DASHBOARD_IMAGE)
	@kubectl -n $(NS) rollout restart deployment gateway worker dashboard 2>/dev/null || true

deploy:
	kubectl apply -k manifests/
	kubectl -n $(NS) rollout status statefulset/postgres --timeout=180s
	kubectl -n $(NS) rollout status statefulset/redis --timeout=180s
	kubectl -n $(NS) rollout status deployment/gateway --timeout=300s
	kubectl -n $(NS) rollout status deployment/worker --timeout=300s
	kubectl -n $(NS) rollout status deployment/dashboard --timeout=180s

# Llena la cola. Correr en otra terminal `make watch` para ver el escalado.
loadgen: clean-jobs
	kubectl apply -f loadgen/job.yaml
	kubectl -n $(NS) wait --for=condition=complete job/loadgen --timeout=300s
	kubectl -n $(NS) logs job/loadgen

clean-jobs:
	@kubectl -n $(NS) delete job loadgen --ignore-not-found

watch:
	watch -n2 'kubectl get hpa,scaledobject,pods -n $(NS)'

status:
	kubectl get all,ingress,hpa,scaledobject -n $(NS)

logs:
	kubectl -n $(NS) logs -l app=worker --tail=50 -f --max-log-requests=10

urls:
	@echo
	@echo "  dashboard   http://localhost/"
	@echo "  api         http://localhost/v1/jobs"
	@echo "  admin       http://localhost/admin/api-keys  (X-Admin-Token: dev-admin-token)"
	@echo
	@echo "  carga:      make loadgen      escalado: make watch"
	@echo

down:
	kind delete cluster --name $(CLUSTER)
