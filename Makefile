# PayVerse developer commands. Run `make help` for the list.
SHELL := /bin/bash
.DEFAULT_GOAL := help

MVN        := ./mvnw -B
COMPOSE    := docker compose
JVM_ARGS   := -Xms128m -Xmx256m -XX:MaxMetaspaceSize=224m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC
export MALLOC_ARENA_MAX := 2
SERVICES   := api-gateway auth-service user-service wallet-service transaction-service payment-service \
              bill-payment-service rewards-service notification-service fraud-risk-service \
              reconciliation-service admin-service

# Testcontainers looks for /var/run/docker.sock, which OrbStack/Docker Desktop do not always create.
# Point it at the active Docker context instead (keeps working after restarts).
ifeq ($(DOCKER_HOST),)
export DOCKER_HOST := $(shell docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)
endif
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE ?= /var/run/docker.sock

# Load .env (if present) and export it to `mvn spring-boot:run`
ifneq (,$(wildcard .env))
include .env
export
endif

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z0-9_%-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ------------------------------------------------------------------ setup
.PHONY: env
env: ## Create .env from .env.example with freshly generated secrets
	@if [ -f .env ]; then echo ".env already exists — leaving it alone"; exit 0; fi; \
	cp .env.example .env; \
	sed -i.bak "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$$(openssl rand -hex 16)|" .env; \
	sed -i.bak "s|^SERVICE_DB_PASSWORD=.*|SERVICE_DB_PASSWORD=$$(openssl rand -hex 16)|" .env; \
	sed -i.bak "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$$(openssl rand -hex 16)|" .env; \
	sed -i.bak "s|^SERVICE_CLIENT_SECRET=.*|SERVICE_CLIENT_SECRET=$$(openssl rand -hex 24)|" .env; \
	sed -i.bak "s|^PAYVERSE_CRYPTO_ENCRYPTION_KEY=.*|PAYVERSE_CRYPTO_ENCRYPTION_KEY=$$(openssl rand -base64 32)|" .env; \
	sed -i.bak "s|^PAYVERSE_CRYPTO_BLIND_INDEX_KEY=.*|PAYVERSE_CRYPTO_BLIND_INDEX_KEY=$$(openssl rand -base64 32)|" .env; \
	rm -f .env.bak; echo "Created .env with random secrets"

.PHONY: testcontainers-reuse
testcontainers-reuse: ## Enable Testcontainers container reuse (~/.testcontainers.properties)
	@grep -q '^testcontainers.reuse.enable=true' ~/.testcontainers.properties 2>/dev/null \
		|| echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
	@echo "Testcontainers reuse enabled"

# ------------------------------------------------------------------ infra
.PHONY: infra-up
infra-up: env ## Start Postgres, Redis, Kafka (KRaft) + topics, Grafana LGTM
	$(COMPOSE) --profile infra up -d
	@echo "Grafana: http://localhost:3000 (admin/admin) | Postgres :$${POSTGRES_PORT:-5433} | Redis :6379 | Kafka :9092"

.PHONY: infra-down
infra-down: ## Stop infra containers (data volumes are kept)
	$(COMPOSE) --profile infra --profile core --profile payments --profile extras down

.PHONY: infra-reset
infra-reset: ## Stop everything AND delete data volumes
	$(COMPOSE) --profile infra --profile core --profile payments --profile extras down -v

.PHONY: up-core
up-core: build ## Build jars and run infra + core services in Docker
	$(COMPOSE) --profile infra --profile core up -d --build

.PHONY: ps
ps: ## Show running containers and memory usage
	@$(COMPOSE) ps
	@docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}" $$(docker ps -q --filter label=com.docker.compose.project=payverse) 2>/dev/null || true

.PHONY: logs
logs: ## Tail logs of all compose containers
	$(COMPOSE) logs -f --tail=100

.PHONY: env-upgrade
env-upgrade: ## Append settings added in later phases to an existing .env (never overwrites)
	@for key in $$(grep -oE '^[A-Z_]+=' .env.example | tr -d '='); do \
		if ! grep -q "^$$key=" .env; then \
			line=$$(grep "^$$key=" .env.example); \
			if [ "$$key" = "SERVICE_CLIENT_SECRET" ]; then line="SERVICE_CLIENT_SECRET=$$(openssl rand -hex 24)"; fi; \
			echo "$$line" >> .env; echo "added $$key"; \
		fi; \
	done

.PHONY: otp
otp: ## Show the latest MOCK OTPs from auth-service logs (Docker or local run log)
	@{ docker logs payverse-auth-service-1 2>/dev/null; cat .run/auth-service.log 2>/dev/null; } | grep -o '\[MOCK SMS\][^"]*' | tail -5

.PHONY: topics
topics: ## List Kafka topics
	docker exec payverse-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# ------------------------------------------------------------------ build & test
.PHONY: build
build: ## Compile and package all modules (skips tests)
	$(MVN) -DskipTests package

.PHONY: test
test: ## Unit tests only (fast, no Docker)
	$(MVN) test

.PHONY: verify
verify: testcontainers-reuse ## Unit + integration tests (Testcontainers) + coverage reports
	$(MVN) verify

.PHONY: clean
clean: ## Remove build output
	$(MVN) -q clean

# ------------------------------------------------------------------ run a service locally
# make run-api-gateway, make run-auth-service, ...
run-%: ## Run a service from source against the infra containers (e.g. make run-api-gateway)
	@if [ ! -d services/$* ]; then echo "Unknown service '$*'. Available: $$(ls services | tr '\n' ' ')"; exit 1; fi
	$(MVN) -q -pl services/$* -am -DskipTests install
	@mkdir -p .run
	$(MVN) -pl services/$* spring-boot:run -Dspring-boot.run.jvmArguments="$(JVM_ARGS)" 2>&1 | tee .run/$*.log

# ------------------------------------------------------------------ housekeeping
.PHONY: docker-prune
docker-prune: ## Remove dangling images, stopped containers and build cache
	docker container prune -f
	docker image prune -f
	docker builder prune -f

.PHONY: smoke
smoke: ## Long live scenario across all services from source (make run-*): every phase's features + exact balances
	python3 e2e/smoke.py

.PHONY: e2e
e2e: ## Whole stack in Docker (12 services) + end-to-end scenario: sign up, add money, transfer, pay a bill, check ledgers
	@if lsof -a -nP -iTCP:8080-8091 -sTCP:LISTEN -c java >/dev/null 2>&1; then \
		echo "Services started with 'make run-*' hold ports 8080-8091: stop them first (or use 'make e2e-local')."; exit 1; fi
	$(MVN) -q -DskipTests package
	$(COMPOSE) --profile infra --profile core --profile payments --profile extras up -d --build --wait --wait-timeout 420
	OTP_SOURCE=docker python3 e2e/e2e.py

.PHONY: e2e-local
e2e-local: ## End-to-end scenario against services already running from source (make run-*)
	OTP_SOURCE=file python3 e2e/e2e.py

.PHONY: load
load: ## k6 load test through the gateway (seeds users first; RATE, BROWSE_RATE, DURATION, USERS)
	python3 load-tests/seed.py --users $${USERS:-20}
	@mkdir -p load-tests/results
	docker run --rm -i --add-host=host.docker.internal:host-gateway -v $(CURDIR)/load-tests:/scripts -w /scripts \
		-e BASE_URL -e RATE -e BROWSE_RATE -e DURATION grafana/k6:1.2.3 run --summary-export=results/summary.json payverse.js

HELM := docker run --rm -v $(CURDIR)/deploy/helm:/charts -w /charts alpine/helm:3.17.3

.PHONY: helm-lint
helm-lint: ## Lint the Helm chart and validate rendered manifests (kubeconform, Kubernetes 1.31) — runs in Docker
	$(HELM) lint payverse
	$(HELM) template payverse payverse | docker run --rm -i ghcr.io/yannh/kubeconform:v0.6.7 -strict -summary -kubernetes-version 1.31.0 -

.PHONY: k8s-render
k8s-render: ## Re-render deploy/k8s/payverse-dev.yaml from the Helm chart
	@{ echo "# Rendered from deploy/helm/payverse (make k8s-render). Dev profile: single-node infra included."; \
	   echo "# Create the Secret first: deploy/k8s/create-secret.sh, then: kubectl apply -n payverse -f deploy/k8s/payverse-dev.yaml"; echo; \
	   $(HELM) template payverse payverse --namespace payverse --set secrets.existingSecret=payverse-secrets; } > deploy/k8s/payverse-dev.yaml

# ------------------------------------------------------------------ web app (React)
.PHONY: web-install web-dev web-test web-build
web-install: ## Install the web app's dependencies (npm ci)
	cd web && npm ci --no-audit --no-fund
web-dev: ## Run the web app on :5173 (proxies /api to the gateway; GATEWAY_URL to override)
	cd web && npm run dev
web-test: ## Web app unit tests (Vitest, API mocked)
	cd web && npm test
web-build: ## Type-check and build the web app into web/dist
	cd web && npm run build

.PHONY: down
down: ## Stop the service containers (infra keeps running)
	$(COMPOSE) --profile core --profile payments --profile extras stop
