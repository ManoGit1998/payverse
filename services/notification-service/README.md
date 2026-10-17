# notification-service

SMS, e-mail and push notifications for PayVerse, plus the platform-wide dead-letter desk. Port
**8088**, database `notification_db`. All providers are **MOCK**: `MockSmsProvider`, `MockEmailProvider`
and `MockPushProvider` log the message instead of delivering it.

## How it works

```
domain event (Kafka) ─▶ DomainEventListeners ─▶ NotificationService.enqueue
                         (once per eventId)      one PENDING row per active template channel
                                                        │
                         NotificationDispatcher (every 1s, row lock per notification)
                                                        ▼
                                        provider.send ─▶ SENT
                                             │ failure
                                             ▼
                              retry after 5s, 10s, 20s, … ─▶ DEAD after 5 attempts (admin can requeue)
```

| Event | Template(s) | Channels |
|---|---|---|
| auth.user-registered | WELCOME | SMS, EMAIL |
| auth.new-device-login | NEW_DEVICE_LOGIN | SMS, PUSH |
| auth.pin-locked | PIN_LOCKED | SMS |
| user.kyc-upgraded | KYC_UPGRADED | PUSH, EMAIL |
| user.bank-account-linked | BANK_ACCOUNT_LINKED | SMS |
| wallet.money-added / money-withdrawn | MONEY_ADDED / MONEY_WITHDRAWN | PUSH / SMS, PUSH |
| wallet.status-changed | WALLET_FROZEN / WALLET_UNFROZEN | SMS, EMAIL / PUSH |
| transaction.completed | PAYMENT_SENT (payer), PAYMENT_RECEIVED (payee) | PUSH / SMS, PUSH |
| transaction.failed | PAYMENT_FAILED | PUSH |
| transaction.money-requested | MONEY_REQUESTED | PUSH |
| payment.collect-requested | COLLECT_REQUESTED | PUSH |
| rewards.cashback-granted / scratch-card-issued | CASHBACK_CREDITED / SCRATCH_CARD_WON | PUSH |

- **Idempotent:** `ProcessedEventGuard` per event, plus `UNIQUE(event_id, user_id, template_code, channel)`.
- **Templates** live in the `templates` table (seeded by `V1__`). `{{name}}` placeholders; event
  values are stripped of braces and control characters, so they can't inject placeholders. Only
  masked data reaches a template (masked account numbers, device names, amounts).
- **Dead-letter desk:** `DeadLetterListener` subscribes to `payverse\..*\.dlt` (every service's DLT)
  and stores each message with its original topic, consumer group and error. Admins replay it to
  the original topic; consumers de-duplicate on eventId. If storing fails, the desk retries every
  5s rather than dead-lettering the dead letter.

## API

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/notifications/me?page&size` | user (inbox + unread count) |
| POST | `/api/v1/notifications/me/{id}/read` | user |
| GET | `/api/v1/admin/templates` | ADMIN, SUPPORT |
| PUT | `/api/v1/admin/templates/{code}/{channel}` | ADMIN (audited) |
| POST | `/api/v1/admin/notifications/{id}/retry` | ADMIN (audited) |
| GET | `/api/v1/admin/dead-letters?onlyOpen` | ADMIN, SUPPORT |
| POST | `/api/v1/admin/dead-letters/{id}/replay` | ADMIN (audited) |

Requests: [../../http/06-notifications.http](../../http/06-notifications.http). Swagger: http://localhost:8088/swagger-ui.html

## Run and test

```bash
make run-notification-service
./mvnw -pl services/notification-service -am verify
```

`NotificationServiceIT` (Postgres, Redis, Kafka) covers a transfer notifying both sides with a
redelivered event, a failing channel going DEAD and being requeued, template editing and
validation, and a poison message travelling to the DLT, being captured, replayed and captured again.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8088` | |
| `NOTIFICATION_MAX_ATTEMPTS` | `5` | Delivery attempts before DEAD |
| `NOTIFICATION_MOCK_FAILING_CHANNELS` | empty | e.g. `EMAIL` makes the mock e-mail provider always fail |
| `POSTGRES_*`, `REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS`, `AUTH_SERVICE_URL`, `OTEL_EXPORTER_OTLP_ENDPOINT` | | as in the other services |
