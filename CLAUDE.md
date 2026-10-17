# CLAUDE.md — PayVerse working notes

Read this first in a new session. The original brief is `PROMPT.md`; the approved architecture
(services, event catalog, DB schemas, APIs, saga) is `docs/PHASE-0-PLAN.md`.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Plan: architecture, events, schemas, APIs | ✅ approved 2026-10-13 |
| 1 | Parent POM, shared libs, api-gateway, docker-compose, Makefile | ✅ done 2026-10-13 |
| 2 | auth-service + user-service | ✅ done 2026-10-14 |
| 3 | wallet-service (double-entry ledger, idempotency, concurrency test) | ✅ done 2026-10-14 |
| 4 | transaction-service + payment-service (saga, UPI sim, QR) | ✅ done 2026-10-17 |
| 5 | notification-service + rewards-service | ⏳ next |
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

## Phase 2 decisions

- KYC tiers are `NONE` → `MIN_KYC` → `FULL_KYC`. `NONE` was added: mobile verified only. Wallet limits per tier come in Phase 3.
- Refresh tokens are opaque (only their SHA-256 is stored), rotated on every use, with family revocation on reuse. Access tokens are RS256 JWTs (15m).
- Signing keys are in `auth_db.signing_keys` with the private key encrypted. auth-service validates its own tokens from that key store; every other service uses the JWKS URL.
- Service-to-service: `POST /api/v1/auth/oauth/token` (client_credentials) issues role `SERVICE`. Dev uses one shared `SERVICE_CLIENT_SECRET`.
- Mobile → userId resolution is owned by auth (`/internal/auth/users/lookup`); user-service stores only the masked mobile.
- (Superseded on 2026-10-14 by the layered restructure: entities are JPA classes with Lombok getters; MapStruct maps them.)
- Mocks: `LogOtpSender` (OTP in logs, `make otp`), `MockKycVerifier` (rejects numbers containing `9999`), `MockDocumentStorage` (discards bytes).
- `@Transactional(noRollbackFor = PayverseException.class)` wherever a failed attempt must be persisted (PIN failures, refresh reuse revocation, KYC rejections).
- Docker Postgres is published on host port **5433** (`POSTGRES_PORT`): the dev Mac runs a native PostgreSQL 18 on 5432.
- Service containers: `mem_limit 512m`, `-Xmx256m`, capped metaspace / code cache / direct memory, `MALLOC_ARENA_MAX=2`
  (measured RSS: gateway ~345 MiB, auth/user ~395 MiB, wallet ~430 MiB, payment ~405 MiB, transaction ~430 MiB).
- JaCoCo `check` fails the build if any `com.payverse.*.service*`, `*.entity*` or `*.model*` package is under 80% line
  coverage (skipped automatically with `-DskipTests`).

## Phase 3 decisions

- Ledger: one LIABILITY account per wallet + fixed-id system accounts (`SystemAccount`). Postings are 2-entry journals;
  balance materialised in `wallets.balance` + `ledger_entries.balance_after`. DB enforces balanced journals (deferred
  trigger), append-only journals/entries, `balance >= 0`, unique idempotency keys, one reversal per journal.
- `WalletPostingService` is the only ledger writer. Lock order: Redis wallet lock (bound to the transaction, released
  afterCompletion) → read wallet → post. Only one user wallet per posting, so no deadlocks.
- Saga contract for Phase 4: `POST /internal/wallets/{userId}/debit|credit|reverse` with `Idempotency-Key`
  (`<txnId>:debit`, `<txnId>:credit`, `<txnId>:compensate`), purpose TRANSFER | BILL_PAYMENT | BILL_REFUND | CASHBACK.
  Retries → 200 + `replayed:true`. Reversals bypass freezes and limits.
- Tier NONE has zero limits (KYC required). Limits are configurable in `payverse.wallet.limits`, with periods in IST.
- Add money uses `MockPaymentGatewayAdapter` inside wallet-service until payment-service takes over in Phase 4
  (`.13` declines). Withdrawals use `MockPayoutAdapter` (`.14` fails, rollback).
- Shared additions in common-web: `ServiceTokenProvider`/`ServiceTokenInterceptor` (client_credentials, cached),
  `RemoteProblemHandler` (remote RFC 9457 → same code/status/extras; 5xx → `DOWNSTREAM_UNAVAILABLE`), `AuditLogger`
  + `V0_3__audit_log.sql` (append-only trigger `payverse_reject_modification()`).
- Service-to-service calls: `RestClient` (spring-boot-starter-restclient) + Spring Cloud CircuitBreaker (Resilience4j,
  3s time limit, only 5xx/IO failures count). PIN verification is never retried (attempts count toward lockout).
- Flyway `out-of-order: true` in every service, because shared `db/common` migrations can be added after a service's V1.
- Hibernate: map `CHAR(n)` columns with `@JdbcTypeCode(SqlTypes.CHAR)` (schema validation is on).

## Phase 4 decisions

- Saga (transaction-service `TransferSaga`): INITIATED → risk → PENDING/NOT_STARTED → debit → DEBITED → credit →
  SUCCESS, or credit refused → COMPENSATING → reverse → FAILED. Each wallet call is idempotent (`<txnId>:debit|credit|compensate`)
  and each state change is its own committed transaction. `TransferService` is deliberately not `@Transactional`.
- Retry classification: 4xx = business refusal (fail or compensate); 5xx/408/409/429 = retry with back-off
  (2^n s, max 5 min). `SagaRecoveryJob` runs every `recovery-interval-ms` (10s); `stale-after` (60s) resumes crashed sagas.
  `SagaLock` (Redis SET NX) means one runner per saga.
- Transfer idempotency is durable at the row level: `UNIQUE(payer_user_id, idempotency_key)` + `request_hash`
  (no `IdempotencyService` here, because the saga commits mid-request). A replay of an unfinished saga nudges it forward.
- Payees: MOBILE (auth lookup), VPA (payment-service, MOCK UPI switch `@payverse` only), QR (payment-service decode;
  dynamic QR fixes the amount), USER (internal). Payees only ever see SUCCESS/REVERSED transactions.
- Risk: `RiskAssessor` with `PermissiveRiskAssessor` (MOCK ALLOW) until fraud-risk-service (Phase 6).
- payment-service owns PG charges now: wallet add money → `PaymentServiceGateway` → `POST /internal/pg/charges`
  (idempotent per key, MOCK `.13` declines). wallet-service's own mock gateway was removed.
- Collect approval → transaction-service `/internal/transfers` with key `collect:<collectId>` (approve twice = one transfer).
  Collect status settles from `transaction.completed/failed` events. Completed transfers create `upi_switch_records`
  (RRN) for reconciliation in Phase 7.
- `PgChargeSettled` event payload now carries `userId` (was `walletId`).
- RestClient URI templates percent-encode `@` in path variables (`ravi%40payverse`); Spring MVC decodes it. WireMock
  stubs must use the encoded path.
- OpenPDF 3 package is `org.openpdf.text` (not `com.lowagie`).
- `e2e/smoke.py` (`make smoke`): live two-user scenario across six services; asserts exact balances + ledger integrity.

## Conventions

- **Package layout (layered, requested by the user on 2026-10-14 — replaces the hexagonal layout of the brief):**
  ```
  com.payverse.<service>
  ├── config      @Configuration, @ConfigurationProperties, Clock
  ├── controller  @RestController (public /api/v1/** and /internal/**)
  ├── dto         request/response records (one per file)
  ├── entity      JPA entities (Lombok @Getter) + their enums; hold state and state rules (e.g. PIN lockout)
  ├── repository  Spring Data repositories and Redis stores
  ├── service     business logic + @Transactional; mock provider adapters (Mock*/Log*) live here too
  ├── security    token/key/hashing components (auth-service)
  └── optional    exception (ErrorCode enum), model (non-persisted value objects), mapper (MapStruct),
                  messaging (Kafka listeners), client (REST clients to other services)
  ```
  Enforced by `LayeredArchitectureRules` (common-test): controllers don't touch repositories, services don't
  depend on controllers, repositories don't depend on services/controllers/DTOs, entities don't depend on
  services/repositories/controllers/DTOs/Spring stereotypes, DTOs don't depend on services/repositories, no
  field injection. Every service has an `ArchitectureTest`.
- Entities: `@Getter` + `@NoArgsConstructor(access = PROTECTED)`, static factories (`User.register(...)`), intention-
  revealing mutators (`pin.recordAttempt(...)`, `account.remove(now)`), no public setters except where a flag is
  genuinely free (`BankAccount.setPrimary`). Encrypted columns use `@Convert(converter = EncryptedStringConverter.class)`;
  blind indexes are computed via an `UnaryOperator<String>` passed in from the service (`blindIndexer::index`).
- Mapping: MapStruct mappers in `mapper` (entity → DTO); small services may use static `Dto.of(entity)` factories.
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
- Profiles/IDs in tests: random UUIDs, random mobiles (`"9" + 9 digits`), random PANs, `TestAadhaar.random()`.
- Logging: JSON (logstash) by default with PII scrubbing; profile `plainlog` gives readable text. Never log
  raw PII, tokens or request bodies.
- Ports: gateway 8080, auth 8081, user 8082, wallet 8083, transaction 8084, payment 8085, bill 8086,
  rewards 8087, notification 8088, fraud 8089, recon 8090, admin 8091. Databases: `auth_db`, `user_db`, `wallet_db`,
  `txn_db`, `payment_db`, `bill_db`, `rewards_db`, `notification_db`, `fraud_db`, `recon_db`, `admin_db`, each
  owned by role `<prefix>_svc` with password `SERVICE_DB_PASSWORD`.
- New service checklist: module in the root `pom.xml`, `Dockerfile` (copy the gateway's), compose entry under the
  right profile using `<<: *service-defaults`, `application.yml` (virtual threads, graceful shutdown, probes,
  OTLP, structured logs; copy auth-service's), the layered packages above, `README.md`, `http/NN-<service>.http`,
  `ArchitectureTest` (copy auth-service's), unit tests for entities/services and a Testcontainers `*IT`.

## Troubleshooting

- **Testcontainers: "Could not find a valid Docker environment"** after a reboot: OrbStack/Docker Desktop may not have
  recreated `/var/run/docker.sock`. The Makefile exports `DOCKER_HOST` from the active Docker context; when running
  `./mvnw` directly, use:
  `export DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}') TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Docker not running at all: `orb start` (OrbStack).

## Commands

```bash
make env && make infra-up        # infra (Postgres, Redis, Kafka + topics, Grafana LGTM)
./mvnw verify                    # everything (needs Docker); `make test` = unit only
make run-api-gateway             # run a service from source (loads .env, logs to .run/<service>.log)
make otp                         # latest MOCK OTPs from the auth-service log
make smoke                       # live end-to-end scenario (auth, user, wallet, payment, transaction + gateway running)
make env-upgrade                 # add settings introduced by later phases to an existing .env
make build && docker compose --profile infra --profile core --profile payments up -d   # services in Docker
make ps                          # containers + memory
```

Grafana: http://localhost:3000 (admin/admin). Gateway: http://localhost:8080.

## Known limitations and TODO

- Gateway rate limiting keys anonymous clients on the remote address. Behind a load balancer, configure
  trusted `X-Forwarded-For` handling (Phase 8, K8s ingress).
- Gateway image is ~630 MB (Ubuntu JRE). Switching to a smaller base is an option for Phase 8.
- `make e2e` intentionally fails until Phase 8.
- Access tokens stay valid until expiry (15m) after logout or device revocation; there is no deny-list yet.
- No penny-drop / name-match verification when linking bank accounts (simulation).
- `SigningKeyService.rotate()` exists but has no admin endpoint yet (admin-service, Phase 7).
- Refunds of completed transfers (SUCCESS → REVERSED) come with admin-service in Phase 7.
- Dynamic QRs are not single-use (they expire); a real PSP would mark them consumed.
- Add money charges the mock gateway inside the request; if the commit failed after a successful charge, recon must
  refund it (Phase 7). Payouts are synchronous in the mock; a real adapter should go through the outbox.
- Reversing a debit does not give back daily-limit usage (conservative).
