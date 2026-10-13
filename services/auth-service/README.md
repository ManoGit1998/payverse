# auth-service

Identity and credentials for PayVerse. Port **8081**, database `auth_db`.

## Features

| Feature | Details |
|---|---|
| Mobile + OTP sign-up / login | One flow: `otp/request` → `otp/verify`. First verification registers the user and publishes `auth.user-registered`. OTPs are 6 digits, valid 5 minutes, single use, stored only as a SHA-256 hash in Redis, max 5 wrong attempts. Limits: 3 requests per 10 minutes per mobile (here) + 5 requests per minute per IP (gateway) |
| **MOCK SMS** | `LogOtpSender` writes the OTP to the log (`make otp` shows the latest). Active only when `OTP_SENDER=log` |
| Access tokens | RS256 JWT, 15 minutes. Claims: `iss`, `sub` (user id), `aud=payverse`, `roles`, `device_id`, `typ=access`, `jti` |
| JWKS | `GET /.well-known/jwks.json`. Signing keys live in `signing_keys` with the private key AES-GCM encrypted. `SigningKeyService.rotate()` retires the active key; retired keys stay published for 24 hours so existing tokens keep working |
| Refresh tokens | Opaque 256-bit, 30 days, stored as a SHA-256 hash, bound to the device. Rotated on every use. Presenting an already-rotated token revokes the whole family (theft detection) |
| Device binding | Each login binds `deviceId`. Logging in on a new device publishes `auth.new-device-login`. Revoking a device ends all its sessions |
| Transaction PIN | 4 or 6 digits, Argon2id. Weak PINs (repeated digits, sequences, common patterns) are rejected. 5 wrong attempts lock it for 30 minutes and publish `auth.pin-locked`. Reset via OTP |
| Service tokens | `POST /api/v1/auth/oauth/token` (client_credentials, form-encoded) issues 5-minute tokens with role `SERVICE`, required for every `/internal/**` API |
| Admin roles | Mobiles in `ADMIN_MOBILES` / `SUPPORT_MOBILES` get those roles at login (local seeding instead of Keycloak) |
| PII | Mobile numbers are encrypted at rest. Lookups go through an HMAC blind index. Events and logs carry only `******1234` |

## API

| Method | Path | Auth |
|---|---|---|
| POST | `/api/v1/auth/otp/request` | public |
| POST | `/api/v1/auth/otp/verify` | public |
| POST | `/api/v1/auth/token/refresh` | public (refresh token) |
| POST | `/api/v1/auth/logout` | user |
| GET | `/api/v1/auth/me` | user |
| GET / DELETE | `/api/v1/auth/devices`, `/api/v1/auth/devices/{deviceId}` | user |
| POST | `/api/v1/auth/pin`, `/api/v1/auth/pin/change` | user |
| POST | `/api/v1/auth/pin/reset/request`, `/api/v1/auth/pin/reset/confirm` | public (OTP) |
| POST | `/api/v1/auth/oauth/token` | client credentials |
| GET | `/.well-known/jwks.json` | public |
| POST | `/internal/auth/pin/verify` | SERVICE |
| POST | `/internal/auth/users/lookup` | SERVICE |

Swagger UI: http://localhost:8081/swagger-ui.html (also aggregated at http://localhost:8080/swagger-ui.html).
Requests: [../../http/01-auth.http](../../http/01-auth.http).

## Events published

`auth.user-registered`, `auth.new-device-login`, `auth.pin-locked` (via the transactional outbox).

## Error codes

`AUTH_INVALID_MOBILE`, `AUTH_INVALID_DEVICE`, `AUTH_OTP_INVALID` (includes `remainingAttempts`), `AUTH_OTP_EXPIRED`,
`AUTH_OTP_ATTEMPTS_EXCEEDED`, `AUTH_OTP_RATE_LIMITED`, `AUTH_INVALID_REFRESH_TOKEN`, `AUTH_REFRESH_TOKEN_REUSED`,
`AUTH_INVALID_CLIENT`, `AUTH_USER_BLOCKED`, `AUTH_USER_NOT_FOUND`, `AUTH_DEVICE_NOT_FOUND`, `AUTH_PIN_INVALID_FORMAT`,
`AUTH_PIN_TOO_WEAK`, `AUTH_PIN_ALREADY_SET`, `AUTH_PIN_NOT_SET`, `AUTH_PIN_INCORRECT` (includes `remainingAttempts`),
`AUTH_PIN_LOCKED` (includes `lockedUntil`).

## Run and test

```bash
make infra-up && make run-auth-service
./mvnw -pl services/auth-service -am verify
```

`AuthServiceIT` runs against Postgres, Redis and Kafka (Testcontainers). It covers sign-up and the event,
JWKS verification, new-device events, OTP limits, refresh rotation and reuse, device revocation, the PIN
lockout/reset cycle, service tokens, user lookup, admin roles and key rotation.

## Configuration

| Env var | Purpose |
|---|---|
| `SERVICE_DB_PASSWORD`, `POSTGRES_HOST`, `POSTGRES_PORT` | `auth_db` connection (role `auth_svc`) |
| `REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS` | OTP store and rate limits, outbox relay |
| `JWT_ISSUER` | `iss` claim (must match every resource server) |
| `PAYVERSE_CRYPTO_ENCRYPTION_KEY`, `PAYVERSE_CRYPTO_BLIND_INDEX_KEY` | PII encryption and blind index |
| `SERVICE_CLIENT_SECRET` | client_credentials secret (shared in dev; per-service secrets or mTLS in prod) |
| `ADMIN_MOBILES`, `SUPPORT_MOBILES` | role seeding |
| `OTP_SENDER` | `log` = MOCK LogOtpSender |
