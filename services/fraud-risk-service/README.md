# fraud-risk-service

Rule-based risk scoring for transfers, with a review queue. Port **8089**, database `fraud_db`,
velocity counters in Redis.

## How a transfer is scored

transaction-service calls `POST /internal/risk/assess` before any money moves (D1). Every enabled rule
that triggers adds its **weight**; the score is capped at 100. A `RiskModel` may raise it (never lower).

| Score | Decision | Effect |
|---|---|---|
| < 50 | ALLOW | transfer proceeds |
| 50–79 | REVIEW | transfer proceeds, a flag is opened |
| ≥ 80 | BLOCK | transfer FAILED with `TXN_RISK_BLOCKED`, a flag is opened, the user is notified |

Thresholds: `payverse.fraud.review-score` / `block-score`. One assessment per transaction: asking again
returns the stored verdict.

| Rule | Default | Weight | Signal |
|---|---|---|---|
| `VELOCITY_COUNT` | more than 5 transfers in 10 min | 40 | Redis sorted set per user (includes this attempt) |
| `VELOCITY_AMOUNT` | more than INR 50,000 in 24 h | 40 | same |
| `AMOUNT_ANOMALY` | INR 1,000+ and over 5× the user's average, after 3+ transfers | 35 | `transaction.completed` (payer) |
| `NEW_DEVICE` | INR 2,000+ from a device first seen < 24 h ago | 50 | `auth.new-device-login` |
| `RECENT_PIN_LOCK` | PIN locked in the last 24 h | 30 | `auth.pin-locked` |
| `LARGE_AMOUNT` | single transfer of INR 50,000+ | 25 | — |

Weights and thresholds live in `risk_rules` and are tunable at runtime (audited). The logic lives in
`RiskRule` beans with the same code (`RiskRules`), so a new rule is one bean plus one row.

**ML-ready:** `RiskModel` is the port for a future scorer; `NoopRiskModel` (MOCK) is active until another
`RiskModel` bean is registered. Assessments record `model_version`.

**If this service is down,** transaction-service fails open for transfers up to INR 2,000
(`payverse.transaction.risk-fail-open-max`) and blocks larger ones.

Every REVIEW/BLOCK publishes `fraud.transaction-flagged` (outbox). Resolving a flag as `CONFIRMED_FRAUD`
can freeze the user's wallet (`POST /internal/wallets/{userId}/freeze`, ADMIN only); if the freeze call
fails the flag stays OPEN.

## API

| Method | Path | Who |
|---|---|---|
| POST | `/internal/risk/assess` | SERVICE |
| GET | `/api/v1/admin/risk/flags?status=OPEN` | ADMIN, SUPPORT |
| POST | `/api/v1/admin/risk/flags/{id}/resolve` | ADMIN, SUPPORT (freezing: ADMIN) |
| GET | `/api/v1/admin/risk/rules` | ADMIN, SUPPORT |
| PUT | `/api/v1/admin/risk/rules/{code}` | ADMIN |
| GET | `/api/v1/admin/risk/assessments?userId` | ADMIN, SUPPORT |

Requests: [../../http/09-risk.http](../../http/09-risk.http).

## Run and test

```bash
make run-fraud-risk-service
./mvnw -pl services/fraud-risk-service -am verify
```

`FraudRiskServiceIT` (Postgres, Redis, Kafka, WireMock as wallet): idempotent ALLOW; a burst from a new
device (event-fed) scoring 90 → BLOCK, flag, outbox event, SUPPORT can't freeze, a failed freeze keeps
the flag open, ADMIN confirms and freezes; PIN lock + amount anomaly (event-fed) = 65 → REVIEW resolved
as a false positive; rule tuning and validation.
