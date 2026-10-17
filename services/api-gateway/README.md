# api-gateway

Edge gateway for PayVerse, built on Spring Cloud Gateway Server WebFlux (reactive). It is the only
service exposed to clients. Port **8080**.

## Responsibilities

| Concern | How |
|---|---|
| Routing | `spring.cloud.gateway.server.webflux.routes` in `application.yml`; downstream URLs come from `*_SERVICE_URL` env vars (compose DNS or localhost) |
| Authentication | OAuth2 resource server. Tokens are verified against auth-service's JWKS (`/.well-known/jwks.json`), plus expiry, issuer, audience `payverse` and `typ=access` (refresh tokens are rejected) |
| Authorization | Public: OTP, token refresh, PIN reset, PG webhook, health, docs. `/api/v1/admin/**` needs ADMIN or SUPPORT. `/internal/**` is never exposed. Everything else needs any valid token |
| Rate limiting | Redis token bucket. Default 20 req/s (burst 40) per user, or per IP when anonymous. OTP and PIN routes: **5 requests/minute per IP** |
| Resilience | A Resilience4j circuit breaker per downstream service. Failures return `503 DOWNSTREAM_UNAVAILABLE` (Problem Details, `Retry-After: 5`) |
| Correlation | Accepts a well-formed `X-Correlation-Id` or generates one, forwards it downstream and echoes it on every response, including 401 and 429 |
| Logging | One JSON access-log line per request (method, path, route, status, userId, duration, correlationId). Bodies, tokens and query strings are never logged |
| CORS | Origins from `CORS_ALLOWED_ORIGINS`. Exposes `X-Correlation-Id`, `Idempotent-Replayed` and the rate-limit headers |
| Security headers | `X-Content-Type-Options`, `X-Frame-Options: DENY`, HSTS, `Referrer-Policy: no-referrer` |
| API docs | Aggregated Swagger UI at `/swagger-ui.html`, which proxies each service's `/v3/api-docs` via `/api-docs/{service}` |
| Observability | OTLP traces, metrics and logs to grafana/otel-lgtm |

## Route table

| Path | Service |
|---|---|
| `/api/v1/auth/otp/**`, `/api/v1/auth/pin/**` (strict rate limit) | auth-service |
| `/api/v1/auth/**` | auth-service |
| `/api/v1/users/**` | user-service |
| `/api/v1/wallets/**` | wallet-service |
| `/api/v1/transfers/**`, `/transactions/**`, `/money-requests/**`, `/split-bills/**` | transaction-service |
| `/api/v1/vpa/**`, `/collect-requests/**`, `/qr/**`, `/pg/**` | payment-service |
| `/api/v1/billers/**`, `/bills/**`, `/mandates/**` | bill-payment-service |
| `/api/v1/rewards/**`, `/api/v1/admin/cashback-rules/**`, `/api/v1/admin/coupons/**` | rewards-service |
| `/api/v1/notifications/**`, `/api/v1/admin/templates/**`, `/admin/notifications/**`, `/admin/dead-letters/**` | notification-service |
| `/api/v1/admin/risk/**` | fraud-risk-service |
| `/api/v1/admin/recon/**` | reconciliation-service |
| `/api/v1/admin/**`, `/api/v1/disputes/**` | admin-service |

## Run

```bash
make infra-up
make run-api-gateway                     # from source
# or in Docker:
make build && docker compose --profile infra --profile core up -d api-gateway
```

Try it with [../../http/00-gateway.http](../../http/00-gateway.http). Until auth-service exists
(Phase 2), every token-protected call returns 401, and auth routes return the 503 fallback.

## Test

```bash
./mvnw -pl services/api-gateway -am verify
```

`ApiGatewayIT` runs the real gateway with Redis (Testcontainers) and WireMock in place of the
downstream services and auth-service's JWKS. It covers JWT validation (expired, refresh, wrong
audience), correlation IDs, admin and internal rules, OTP rate limiting, the circuit-breaker
fallback, CORS and health.

## Configuration

| Env var | Default |
|---|---|
| `SERVER_PORT` | `8080` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / empty |
| `AUTH_SERVICE_URL` … `ADMIN_SERVICE_URL` | `http://localhost:808x` |
| `JWT_ISSUER` | `http://localhost:8081` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` |
| `LOG_FORMAT` | `logstash` (JSON). Activate profile `plainlog` for readable text |
