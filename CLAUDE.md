# CLAUDE.md — PayVerse working notes

Read this first in a new session. The original brief is `PROMPT.md`; the approved architecture
(services, event catalog, DB schemas, APIs, saga) is `docs/PHASE-0-PLAN.md`.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Plan: architecture, events, schemas, APIs | ✅ approved 2026-10-13 |
| 1 | Parent POM, shared libs, api-gateway, docker-compose, Makefile | ✅ done 2026-10-13 |
| 2 | auth-service + user-service | ⏳ next |
| 3 | wallet-service (double-entry ledger, idempotency, concurrency test) | |
| 4 | transaction-service + payment-service (saga, UPI sim, QR) | |
| 5 | notification-service + rewards-service | |
| 6 | bill-payment-service + fraud-risk-service | |
| 7 | reconciliation-service + admin-service | |
| 8 | Dashboards, k6, K8s/Helm, GitHub Actions, `make e2e` | |

Workflow per phase: build → `./mvnw verify` green → update this file → small commits → summarize
with run/test steps → wait for the user to say "continue".

## Versions (verified 2026-10-13)

Spring Boot **4.1.1**, Spring Cloud **2025.1.3** (Gateway 5.0.3), Java language level **21** (local
JDK is 25), Maven wrapper 3.9.x, Testcontainers 2.0.5, springdoc 3.1.1, MapStruct 1.6.3,
ArchUnit 1.5.1, WireMock 3.13.2, Resilience4j 2.4.0, Jackson **3** (`tools.jackson.*`; annotations
are still `com.fasterxml.jackson.annotation`).
Images: `postgres:17-alpine`, `redis:8.8-alpine`, `apache/kafka:4.3.1`, `grafana/otel-lgtm:0.36.0`,
`eclipse-temurin:21-jre` (all arm64).

**Gotcha:** `opentelemetry-logback-appender-1.0` must match the OpenTelemetry API version managed by
Boot (OTel 1.62 → appender **2.28.0-alpha**). A newer appender fails at runtime with
`NoClassDefFoundError ExtendedAttributeKey`.

## Architecture decisions (D1–D11 from the plan, all approved)

- D1 Transfer saga: transaction-service calls wallet-service over REST (Idempotency-Key per step); Kafka carries facts only.
- D2 Transfers go through a `TRANSFER_SUSPENSE` ledger account; compensation = suspense → sender.
- D3 Balance = SUM(ledger entries), materialised in `wallets.balance` in the same transaction, cached in Redis, checked nightly.
- D4 Concurrency: Redis lock per wallet + `@Version`; proven by a 100-parallel-transfer test (Phase 3).
- D5 JSON events with a versioned envelope, no Schema Registry. D6 Polling outbox relay, no Debezium.
- D7 Gateway is WebFlux; every other service is Spring MVC on virtual threads.
- D8 Service URLs come from properties/env (`*_SERVICE_URL`); no Eureka or Config Server yet.
- D9 PII: AES-256-GCM `EncryptedStringConverter` + HMAC blind index for lookups; masking with `@Masked` and log scrubbing.
- D10 API collections are `.http` files in `http/`. D11 All external providers are named `Mock*` adapters.

## Conventions

- Packages: `com.payverse.<service>.{api,application,domain,infrastructure}`. `domain` may depend only on
  the JDK, itself, `com.payverse.common.money` and `com.payverse.common.error` (base package). JPA entities
  live in `infrastructure.persistence` and are mapped to domain objects. Every service adds an
  `ArchitectureTest` using `HexagonalArchitectureRules` (see `libs/common-test`).
- Money: `Money` only (never double/float); DB `NUMERIC(19,2)` + `CHAR(3)` currency; wire format is a string `"100.00"`.
- Errors: throw `PayverseException(ErrorCode, detail)`. Each service gets its own `ErrorCode` enum with codes
  prefixed by the service, e.g. `WALLET_INSUFFICIENT_BALANCE`.
- Money-moving endpoints: `IdempotencyService.execute(scope, key, request, type, action)`. The action's
  `@Transactional` joins the idempotency transaction.
- Events: `OutboxPublisher.publish(EventType, aggregateId, payload)` inside the business transaction.
  Consumers: `codec.decode(...)` + `ProcessedEventGuard.handleOnce(...)`. Add new topics to `Topics`, `EventType`
  **and** `infra/kafka/create-topics.sh` (`TopicScriptSyncTest` enforces this).
- Flyway: `spring.flyway.locations=classpath:db/migration,classpath:db/common`. Service migrations start at `V1__`.
- Tests: `*Test` = unit (surefire), `*IT` = Testcontainers integration (failsafe, run by `verify`). Import
  `PostgresTestcontainer` / `RedisTestcontainer` / `KafkaTestcontainer`. Postgres gets one database per module
  (named from `spring.application.name`). Use random UUIDs in test data because containers are reused.
- Security: services get the default chain from `common-security`. Add public paths via
  `payverse.security.public-paths` or a `PayverseHttpSecurityCustomizer` bean. Service-to-service calls to
  `/internal/**` need a token with role `SERVICE` (auth-service client-credentials, Phase 2).
- Logging: JSON (logstash) by default with PII scrubbing; profile `plainlog` gives readable text. Never log
  raw PII, tokens or request bodies.
- Ports: gateway 8080, auth 8081, user 8082, wallet 8083, transaction 8084, payment 8085, bill 8086,
  rewards 8087, notification 8088, fraud 8089, recon 8090, admin 8091. Databases: `auth_db`, `user_db`, `wallet_db`,
  `txn_db`, `payment_db`, `bill_db`, `rewards_db`, `notification_db`, `fraud_db`, `recon_db`, `admin_db`, each
  owned by role `<prefix>_svc` with password `SERVICE_DB_PASSWORD`.
- New service checklist: module in the root `pom.xml`, `Dockerfile` (copy the gateway's), compose entry under the
  right profile using `<<: *service-defaults`, `application.yml` (virtual threads, graceful shutdown, probes,
  OTLP, structured logs; copy the gateway's management/logging blocks), `README.md`, `http/NN-<service>.http`,
  `ArchitectureTest`, and an IT.

## Commands

```bash
make env && make infra-up        # infra (Postgres, Redis, Kafka + topics, Grafana LGTM)
./mvnw verify                    # everything (needs Docker); `make test` = unit only
make run-api-gateway             # run a service from source (loads .env)
make build && docker compose --profile infra --profile core up -d   # services in Docker
make ps                          # containers + memory
```

Grafana: http://localhost:3000 (admin/admin). Gateway: http://localhost:8080.

## Known limitations and TODO

- Gateway rate limiting keys anonymous clients on the remote address. Behind a load balancer, configure
  trusted `X-Forwarded-For` handling (Phase 8, K8s ingress).
- Gateway image is ~630 MB (Ubuntu JRE). Switching to a smaller base is an option for Phase 8.
- `make e2e` intentionally fails until Phase 8.
- Audit log (`audit_log` with an append-only trigger) arrives with the first financial action (Phase 3).
