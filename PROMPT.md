# Project: "PayVerse" — A PhonePe-style Digital Wallet Platform

You are a senior backend architect and engineer. Build a production-grade digital wallet
and payments platform using Java 21 and Spring Boot microservices. Work in PHASES (listed
below). Before writing any code, propose the full architecture, folder structure and
phase plan, and wait for my approval. After each phase, stop, summarize what was built,
show exactly how to run and test it, and wait for me to say "continue".

## Local Machine Constraints (IMPORTANT — design everything around these)
I'm developing on a MacBook Air M5 (Apple Silicon, ARM64), 16 GB RAM, 512 GB SSD,
using OrbStack as the Docker runtime.
- All Docker images must support linux/arm64.
- docker-compose: ONE PostgreSQL container with an init script that creates a separate
  database per service; ONE Redis; ONE Kafka broker in KRaft mode (no ZooKeeper);
  observability via the single grafana/otel-lgtm container. Set mem_limit on every container.
- Total memory for infra containers must stay under ~4 GB.
- Every Spring Boot service runs with -Xms128m -Xmx256m and virtual threads enabled.
- Use docker-compose profiles (`infra`, `core`, `payments`, `extras`) so I can start only
  what the current phase needs.
- Provide a Makefile with targets: infra-up, infra-down, run-<service>, build, test,
  clean, docker-prune, e2e.
- Testcontainers: enable container reuse to save time and memory.
- No Eureka, no Config Server, no Keycloak for local dev (see Tech Stack). Keep the code
  structured so they can be added later without major rewrites.

## Tech Stack (use the latest stable versions; verify on start.spring.io and Maven Central)
- Java 21: records, sealed interfaces, pattern matching for switch, virtual threads
  (spring.threads.virtual.enabled=true)
- Spring Boot (latest stable) and the matching Spring Cloud release train
- Build: Maven multi-module (parent POM with dependencyManagement, Spring Cloud BOM)
- Spring Cloud Gateway as API gateway
- Service discovery: Docker Compose DNS / localhost ports; config via Spring profiles
  (dev, test, prod) and .env files
- Security: auth-service issues RS256-signed JWTs and exposes a JWKS endpoint; gateway
  and all services validate tokens using Spring Security OAuth2 Resource Server
- PostgreSQL (separate database per service), Flyway migrations, Spring Data JPA
- Redis: caching, OTP storage, idempotency keys, distributed locks, rate limiting
- Apache Kafka (KRaft): event-driven communication between services
- Resilience4j: circuit breaker, retry, rate limiter, bulkhead, timeouts
- MapStruct for mapping, Lombok only where records don't fit, Bean Validation
- springdoc-openapi for Swagger UI in every service
- Observability: Micrometer + OpenTelemetry (traces, metrics, logs) exported to
  grafana/otel-lgtm; structured JSON logging with correlation IDs
- Testing: JUnit 5, Mockito, Testcontainers (Postgres, Kafka, Redis), WireMock for
  inter-service calls, ArchUnit for architecture rules
- Containers: Dockerfile per service (layered jars, non-root user, arm64-compatible base)
- Later phase: Kubernetes manifests + Helm chart, GitHub Actions CI (build, test, JaCoCo
  coverage, image build)

## Microservices
1. api-gateway — routing, JWT validation, rate limiting (Redis), CORS, request logging,
   correlation-ID injection
2. auth-service — mobile-number + OTP sign-up/login (OTP printed to logs in dev), JWT
   access + refresh tokens, JWKS endpoint, device binding, 4/6-digit transaction PIN
   (hashed with Argon2), PIN reset, lockout after failed attempts
3. user-service — profile, KYC tiers (MIN_KYC / FULL_KYC with document-upload stub),
   linked bank accounts, saved contacts/beneficiaries
4. wallet-service — wallet creation, balance, add money, withdraw to bank,
   DOUBLE-ENTRY LEDGER (every movement = debit + credit entries; balance derived from
   ledger and cached), wallet limits based on KYC tier, freeze/unfreeze
5. transaction-service — P2P transfer (mobile number / UPI ID / QR), request money,
   split bill, transaction history with filters + pagination, statements (PDF/CSV),
   orchestrated SAGA for transfers with compensation on failure
6. payment-service — SIMULATED UPI layer: VPA creation (name@payverse), collect
   requests, dynamic and static QR generation (ZXing), MockUpiSwitchAdapter behind an
   interface so a real provider can be plugged in later, mock payment-gateway adapter
   for "add money"
7. bill-payment-service — mobile recharge, electricity, DTH, FASTag, credit card bill
   (mock biller adapters), scheduled auto-pay mandates
8. rewards-service — cashback rules engine, scratch cards, coupons, referral bonus
   (consumes transaction events from Kafka)
9. notification-service — consumes Kafka events; SMS, email, push via pluggable
   providers with mock implementations; templates; retry + dead-letter topic
10. fraud-risk-service — rule-based risk scoring (velocity checks, amount anomalies,
    new device), blocks or flags transactions; designed for a future ML model
11. reconciliation-service — scheduled Spring Batch jobs that reconcile ledger vs
    payment-provider records and report mismatches
12. admin-service — back-office APIs: user search, freeze wallet, disputes, refund
    approval, audit logs (roles: ADMIN, SUPPORT)

## Non-Negotiable Financial Correctness Rules
- Never use double/float for money. Use a Money value object (BigDecimal, scale 2,
  HALF_EVEN, currency INR by default).
- Every write API that moves money requires an Idempotency-Key header; store results in
  Redis + DB so retries return the same response.
- Optimistic locking (@Version) on wallet rows plus a lock for concurrent debits; prove
  correctness with a concurrency test (100 parallel transfers, final balances exact).
- Transactional Outbox pattern for publishing Kafka events (no dual writes); idempotent
  Kafka consumers using a processed-events table.
- Explicit transaction state machine: INITIATED → PENDING → SUCCESS / FAILED / REVERSED,
  with allowed transitions enforced in the domain.
- Immutable audit log for every financial and admin action.
- Encrypt PII at rest (bank account numbers, KYC IDs) and mask it in logs and responses.

## Cross-Cutting Requirements
- Hexagonal architecture inside each service: api, application, domain, infrastructure
  packages. The domain layer must not depend on Spring (enforce with ArchUnit).
- Global error handling with RFC 9457 Problem Details and consistent error codes.
- API versioning (/api/v1/...), pagination, sorting, validation on all inputs.
- OWASP basics: input validation, no secrets in code (env vars), security headers,
  strict rate limits on OTP and PIN endpoints.
- Health checks, readiness/liveness probes, graceful shutdown.
- Each service has its own README; root README has a Mermaid architecture diagram,
  setup steps, and .http files (or a Bruno collection) for every endpoint.
- Minimum 80% unit-test coverage on domain and application layers; integration tests
  with Testcontainers for every service.

## Phases
Phase 0 — Plan: architecture diagram, service boundaries, event catalog (Kafka topics +
          payload schemas), DB schema per service, API list. Wait for approval.
Phase 1 — Foundation: parent POM, shared modules (common-error, common-security,
          common-events, common-money), api-gateway, docker-compose (Postgres, Redis,
          Kafka KRaft, otel-lgtm) with profiles and memory limits, Makefile.
Phase 2 — auth-service + user-service (OTP login, JWT + JWKS, PIN, KYC tiers).
Phase 3 — wallet-service with double-entry ledger, idempotency, concurrency tests.
Phase 4 — transaction-service + payment-service (P2P, UPI simulation, QR, saga).
Phase 5 — notification-service + rewards-service (Kafka consumers, outbox, DLQ).
Phase 6 — bill-payment-service + fraud-risk-service.
Phase 7 — reconciliation-service + admin-service.
Phase 8 — Grafana dashboards, k6 load tests, Kubernetes + Helm, GitHub Actions CI, and
          an end-to-end script (`make e2e`) that signs up two users, adds money,
          transfers, pays a bill, and verifies ledger balances.

## Working Rules
- Create CLAUDE.md at the repo root recording architecture decisions, conventions,
  current phase status, and run commands. Update it at the end of every phase so a
  fresh session can continue from it.
- Run `mvn verify` after each phase and fix all failures before reporting done.
- Never stub silently: name mocks clearly (e.g., MockUpiSwitchAdapter) and document them.
- Make small, logical git commits with clear messages at the end of each phase.

Start with Phase 0 now.
