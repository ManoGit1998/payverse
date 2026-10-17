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
| 5 | notification-service + rewards-service | ✅ done 2026-10-17 |
| 6 | bill-payment-service + fraud-risk-service | ✅ done 2026-10-17 |
| 7 | reconciliation-service + admin-service | ✅ done 2026-10-17 |
| 8 | Dashboards, k6, K8s/Helm, GitHub Actions, `make e2e` | ✅ done 2026-10-17 |

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

## Phase 5 decisions

- notification-service: one PENDING row per (event, user, template, channel) — `UNIQUE` + `ProcessedEventGuard`.
  `NotificationDispatcher` (1s) sends each under a row lock in its own transaction; back-off 5s·2^n, DEAD after 5
  attempts; admins requeue (`/api/v1/admin/notifications/{id}/retry`). Templates in DB with `{{name}}` placeholders;
  values are stripped of braces/control chars. MOCK providers log; `NOTIFICATION_MOCK_FAILING_CHANNELS=EMAIL` forces failures.
- Dead-letter desk lives in notification-service: `topicPattern = payverse\..*\.dlt` captures every service's DLT
  (`dead_letter_events`, with original consumer group + error); admin replay re-publishes to the original topic.
  Its own container factory retries forever (no `.dlt.dlt`). Pattern subscriptions see new topics after
  `metadata.max.age.ms` (30s; 1s in tests).
- rewards-service: rule conditions are typed columns, not jsonb (validated by DB checks and `CashbackRule`). First
  matching rule with budget wins, one reward per event. Budget reserved with an atomic `UPDATE … WHERE spent + x <= budget`;
  `spent` is read-only in the entity + `@DynamicUpdate`, so admin edits never overwrite it.
- Cashback credit: reward rows PENDING_CREDIT → `RewardCreditService` (1s) → wallet `credit` purpose CASHBACK, key
  `reward:<id>` → CREDITED + `rewards.cashback-granted` (a fact). Wallet calls never happen inside a DB transaction,
  except clawback (`reward:<id>:clawback`, inside the consumer transaction; redelivery replays it).
- Scratch/redeem endpoints take no Idempotency-Key: both are naturally idempotent and only create a reward.
- Referral codes: 8 chars from an alphabet without look-alikes; `UserRegistered.referralCode` is the code the new
  user entered. Bonus on the first upgrade to MIN_KYC/FULL_KYC (referrer 100, referee 50).
- New event `rewards.scratch-card-issued` (→ notification SCRATCH_CARD_WON).
- WireMock `matchingJsonPath("$.amount", equalTo("1.00"))` fails for numeric JSON (read as 1.0): use a filter
  expression `$[?(@.amount == 1.00)]`.

## Phase 6 decisions

- fraud-risk-service: score = sum of weights of triggered rules (cap 100), `RiskModel` may only raise it (MOCK
  `NoopRiskModel`, version `rules-v1`). ALLOW < 50 ≤ REVIEW < 80 ≤ BLOCK. Rule logic = `RiskRule` beans; weights/thresholds
  = `risk_rules` rows (typed columns, admin-tunable). One assessment per transaction (`UNIQUE(transaction_id)`).
  REVIEW/BLOCK → flag + `fraud.transaction-flagged`. Velocity = Redis ZSET per user (`<txnId>|<amount>`, recorded at
  assessment, idempotent). Signals from events: `known_devices` (new-device-login only — the registration device is
  never "new"), `user_risk_profiles` (completed transfers as payer, last PIN lock).
- transaction-service: `RemoteRiskAssessor` is the default (`RISK_ASSESSOR=permissive` for the MOCK). Fail-open up to
  `risk-fail-open-max` (2000) when fraud is down, fail-closed above. `transactions.device_id` (from the JWT) + `risk_score`/
  `risk_decision` (V2). Collect approvals have no device (null).
- Confirming fraud can freeze the wallet (ADMIN only); the remote freeze runs before the flag is resolved, so a failed
  freeze leaves the flag OPEN.
- bill-payment-service: same saga style as transfers (debit `<id>:debit` → biller (idempotent per payment id) → SUCCESS, or
  declined → refund `<id>:refund` → FAILED). Recovery job resumes due/stale PENDING payments; `@Version` stops concurrent
  runners. Account refs encrypted + masked. `MockBillerAdapter`: `0000` unknown account, `.13` decline, `.14` one timeout.
- Mandates: PIN at creation is the consent; runs claimed under a row lock with `UNIQUE(mandate_id, scheduled_for)` and paid
  with key `mandate:<id>:<yyyyMMddHHmm UTC>`; bills above `maxAmount` are SKIPPED. Monthly in IST calendar terms.
- Notifications: BILL_PAID, BILL_PAYMENT_FAILED, AUTOPAY_FAILED (failed/skipped runs), PAYMENT_BLOCKED (BLOCK only).
- A `@Configuration` class and one of its `@Bean` methods must not share a name (`noopRiskModel` → BeanDefinitionOverrideException).

## Phase 7 decisions

- Refunds (transaction-service `RefundService`, `POST /internal/transactions/{id}/refund {refundId, reason}`): full refunds
  only; reverse the payee's credit journal (`refund:<id>:payee`), then the payer's debit journal (`refund:<id>:payer`), then
  SUCCESS → REVERSED + `transaction.reversed` (event `transactionId` = refund id, `originalTransactionId` = the transfer).
  Payee reversal refused (spent) → refund FAILED, nothing moved, a new refund id may retry. 200 COMPLETED/FAILED, 202 PENDING
  (caller retries). Columns `refund_*` on `transactions` (V3); `SagaLock` guards against a concurrent saga run.
- admin-service is the only caller of refunds (maker-checker): REQUESTED → APPROVED by a different ADMIN (entity + DB check)
  → executed; PROCESSING retried every 30 s. Approval + `admin.refund-approved` + audit are committed before the remote call.
  Disputes (users, `/api/v1/disputes`, gateway route added) resolve as REJECTED or REFUND (creates the refund request).
- Admin actions use the shared `audit_log`; `/api/v1/admin/audit-logs` reads admin-service's own log.
- Signing-key rotation: `POST /internal/auth/signing-keys/rotate` (only client `admin-service`), exposed as ADMIN
  `/api/v1/admin/security/signing-keys/rotate`; retired keys stay in the JWKS for a day.
- Internal feeds for recon (role SERVICE, `[from, to)`, paged, max 1000): wallet `/internal/ledger/journals?type`,
  payment `/internal/pg/charges?status` (GET; POST is the charge) and `/internal/upi/switch-records`, transaction
  `/internal/transactions?status`. user-service `/internal/users/search?name&kycTier`.
- reconciliation-service: Spring Batch 6 (`spring-boot-starter-batch-jdbc`); metadata tables copied into Flyway V1,
  `spring.batch.jdbc.initialize-schema=never`, `spring.batch.job.enabled=false`. One `Job` built per run (no @StepScope);
  `JobOperator.start` on a virtual thread. Packages moved in Batch 6: `org.springframework.batch.core.job.Job`,
  `...core.step.Step`, `...infrastructure.item.*`; `StepBuilder.chunk(int)` → `ChunkOrientedStepBuilder` + `.transactionManager()`.
- MockMvc `get("/path?x=a%20b")` re-encodes `%` (→ `%2520`); pass query values with `.param(...)`.
- Kafka does not order events across topics: rewards-service got `user.kyc-upgraded` before `auth.user-registered`
  and lost the referral bonus. Fix: `kyc_qualified_users` (rewards V2) remembers qualification; a referral created
  later pays out at once. Any consumer joining two topics needs this kind of "remember and reconcile" state.

## Phase 8 decisions

- Business metrics without touching business code: `OutboxPublisher` counts each event after commit (`payverse.events`
  + `payverse.events.amount` for payloads with an `amount`), plus outbox backlog/age gauges; fraud and notification add
  `payverse.risk.decisions|rule.hits` and `payverse.notifications`. **Counters are registered at 0 on startup**: with OTLP
  push the first export already contains the first increments, which Prometheus `increase()` cannot see.
- Dashboards are generated (`infra/grafana/build_dashboards.py` → `infra/grafana/dashboards` + a copy in the chart);
  mounted into otel-lgtm via `/otel-lgtm/grafana/conf/provisioning/dashboards/payverse.yaml`. CI fails if out of date.
- Images: `eclipse-temurin:21-jre-alpine` (gateway 434 MB, services ~510 MB) with a busybox-`wget` HEALTHCHECK on
  `/actuator/health/readiness`, so `docker compose up --wait` means ready.
- Memory: Spring MVC services use 140–157 MiB metaspace — the old 160m cap killed wallet under load. Now
  `MaxMetaspaceSize=224m`, container limit 640m (compose) / heap = 40% of the limit (Helm). auth peaks ~550 MiB (Argon2).
- `make e2e`: whole stack in Docker (`up --build --wait`), scenario in `e2e/e2e.py` (OTPs from `docker logs`); it checks
  balances three ways (arithmetic incl. cashback, each wallet's ledger sum, integrity report). It refuses to run while
  `make run-*` services hold the ports. Clients must handle the gateway's 429 on OTP/PIN (5/min/IP): the scripts wait.
- `docker compose logs <svc>` fails for services outside the active profiles ("no such service: kafka"); use `docker logs`.
- k6 runs in Docker (`grafana/k6:1.2.3`, `host.docker.internal`); users are seeded through auth-service directly.
- Helm: one chart, a generic per-service template from `values.services`; secrets generated once (`lookup` +
  `resource-policy: keep`) or `existingSecret`; gateway `SERVER_FORWARD_HEADERS_STRATEGY=framework` (ingress must
  overwrite `X-Forwarded-For`). `deploy/k8s` is rendered with an existing Secret (never commit generated keys).
  Tools run from Docker images (helm, kubeconform, actionlint) — none installed locally.
- `make env` uses `sed -i.bak` (works with BSD and GNU sed; CI is Linux).

## Web app (web/, added 2026-10-17)

- React 19 + TypeScript 5.8 + Vite 6 + React Router 7 + TanStack Query 5 (pinned to well-known majors, not the newest
  React Router 8 / Vite 8 / TS 7). Plain CSS tokens (light/dark), no UI library. Vitest 3 + Testing Library.
- Talks to the gateway only (`/api/v1`); Vite proxies `/api` in dev (port 5173 — Grafana owns 3000) and strips the
  browser's `Origin` header, otherwise the gateway CORS filter answers 403 (only `CORS_ALLOWED_ORIGINS` pass).
- `src/api/client.ts`: memory access token + localStorage refresh token, single-flight refresh, and a retry without refreshing
  when another request already rotated the token (a `setTimeout`-cleared promise caused a stale-promise race — fixed).
  `useIdempotencyKey`: one key per payload. Mutations never auto-retry. PIN asked per action via `usePin()`.
- Types in `src/api/types.ts` mirror the Java DTO records; endpoints in `src/api/endpoints.ts` mirror `http/*.http`.
  Note the two page shapes: `totalItems` (wallet/transaction/payment) vs `totalElements` (others).
- Node ≥ 22 defines a global `localStorage` that shadows jsdom's: `src/test/setup.ts` installs an in-memory Storage.
- npm 11 skips esbuild's postinstall; the platform binary comes from its optional dependency, builds work.
- Not yet run against a live backend (services were intentionally kept down while building it).

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
make smoke                       # long live scenario, all phases (services from source via make run-*)
make e2e                         # whole stack in Docker + the brief's end-to-end scenario
make load                        # k6 load test against a running stack
make helm-lint                   # Helm chart lint + kubeconform (Docker)
make web-install && make web-dev # React app on :5173 (proxies /api to the gateway)
make env-upgrade                 # add settings introduced by later phases to an existing .env
make build && docker compose --profile infra --profile core --profile payments --profile extras up -d   # services in Docker
make ps                          # containers + memory
```

Grafana: http://localhost:3000 (admin/admin). Gateway: http://localhost:8080.

## Known limitations and TODO

- The Helm chart is validated (lint + kubeconform) but has not been deployed to a live cluster in this repo's checks.
- The gateway's 429 responses carry no `Retry-After` header (clients back off on their own).
- Dev infra in the chart is single-node (no HA, no backups); production must use managed Postgres/Redis/Kafka.
- Access tokens stay valid until expiry (15m) after logout or device revocation; there is no deny-list yet.
- No penny-drop / name-match verification when linking bank accounts (simulation).
- Refunds are full refunds only; reversing a refund does not give back daily-limit usage.
- Dynamic QRs are not single-use (they expire); a real PSP would mark them consumed.
- Add money charges the mock gateway inside the request; if the commit failed after a successful charge, the
  PG_VS_LEDGER recon run reports CHARGED_NOT_CREDITED (refunding at the gateway is manual). Payouts are synchronous in the mock; a real adapter should go through the outbox.
- Reversing a debit does not give back daily-limit usage (conservative).
