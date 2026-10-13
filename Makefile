# PayVerse developer commands. Run `make help` for the list.
SHELL := /bin/bash
.DEFAULT_GOAL := help

MVN        := ./mvnw -B
COMPOSE    := docker compose
JVM_ARGS   := -Xms128m -Xmx256m -XX:+UseSerialGC
SERVICES   := api-gateway auth-service user-service wallet-service transaction-service payment-service \
              bill-payment-service rewards-service notification-service fraud-risk-service \
              reconciliation-service admin-service

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
	sed -i '' "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$$(openssl rand -hex 16)|" .env; \
	sed -i '' "s|^SERVICE_DB_PASSWORD=.*|SERVICE_DB_PASSWORD=$$(openssl rand -hex 16)|" .env; \
	sed -i '' "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$$(openssl rand -hex 16)|" .env; \
	sed -i '' "s|^PAYVERSE_CRYPTO_ENCRYPTION_KEY=.*|PAYVERSE_CRYPTO_ENCRYPTION_KEY=$$(openssl rand -base64 32)|" .env; \
	sed -i '' "s|^PAYVERSE_CRYPTO_BLIND_INDEX_KEY=.*|PAYVERSE_CRYPTO_BLIND_INDEX_KEY=$$(openssl rand -base64 32)|" .env; \
	echo "Created .env with random secrets"

.PHONY: testcontainers-reuse
testcontainers-reuse: ## Enable Testcontainers container reuse (~/.testcontainers.properties)
	@grep -q '^testcontainers.reuse.enable=true' ~/.testcontainers.properties 2>/dev/null \
		|| echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
	@echo "Testcontainers reuse enabled"

# ------------------------------------------------------------------ infra
.PHONY: infra-up
infra-up: env ## Start Postgres, Redis, Kafka (KRaft) + topics, Grafana LGTM
	$(COMPOSE) --profile infra up -d
	@echo "Grafana: http://localhost:3000 (admin/admin) | Postgres :5432 | Redis :6379 | Kafka :9092"

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
	$(MVN) -pl services/$* spring-boot:run -Dspring-boot.run.jvmArguments="$(JVM_ARGS)"

# ------------------------------------------------------------------ housekeeping
.PHONY: docker-prune
docker-prune: ## Remove dangling images, stopped containers and build cache
	docker container prune -f
	docker image prune -f
	docker builder prune -f

.PHONY: e2e
e2e: ## End-to-end scenario (implemented in Phase 8)
	@echo "make e2e is implemented in Phase 8 (signs up two users, adds money, transfers, pays a bill, checks ledgers)."; exit 1
