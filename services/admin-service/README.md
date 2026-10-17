# admin-service

The back office (roles ADMIN and SUPPORT, no Keycloak: `ADMIN_MOBILES` / `SUPPORT_MOBILES` in auth-service).
Port **8091**, database `admin_db`. Every action is written to the append-only `audit_log` with the
acting user's id.

| Area | What | Who |
|---|---|---|
| Users | search by mobile (exact, via auth) or name/KYC tier (user-service), user view with wallet | ADMIN, SUPPORT |
| Wallets | freeze (reason) / unfreeze via wallet-service | ADMIN |
| Disputes | users raise them on their SUCCESS transfers (`/api/v1/disputes`); agents assign and resolve REJECTED or REFUND | users / ADMIN, SUPPORT |
| Refunds | maker-checker: request (SUPPORT or ADMIN) → approve or reject by a **different** ADMIN | see left |
| Audit | `/api/v1/admin/audit-logs?actor&action&entityType&entityId&from&to` | ADMIN, SUPPORT |
| Security | rotate the JWT signing key (auth-service keeps the old key in the JWKS for a day) | ADMIN |

## Refunds

```
REQUESTED ─approve (other ADMIN)─► APPROVED ─► transaction-service /internal/transactions/{id}/refund
                                                  ├─ COMPLETED  (payee credit reversed, payer debit reversed, SUCCESS → REVERSED)
                                                  ├─ FAILED     (e.g. the payee already spent it — nothing moved)
                                                  └─ PROCESSING (unreachable / still running → retried every 30 s, 2^n back-off)
REQUESTED ─reject─► REJECTED
```

- Maker ≠ checker is enforced in the entity and by a DB check (`decided_by <> requested_by`).
- At most one live refund per transaction (partial unique index); refunds are full refunds.
- The approval (+ `admin.refund-approved` event + audit) is committed before transaction-service is
  called, and the call is idempotent per refund id, so a retry never refunds twice.
- transaction-service publishes `transaction.reversed`, which makes rewards-service claw back any cashback.

## API

Requests: [../../http/11-admin.http](../../http/11-admin.http). Swagger: http://localhost:8091/swagger-ui.html

## Run and test

```bash
make run-admin-service
./mvnw -pl services/admin-service -am verify
```

`AdminServiceIT` (Postgres, Redis, Kafka, WireMock for auth/user/wallet/transaction): dispute → REFUND →
approval by another admin → COMPLETED (+ event + audit trail); maker-checker refusal, duplicate refunds,
FAILED refunds; transaction-service down → PROCESSING → retried to COMPLETED; rejection; user search,
freeze/unfreeze, key rotation and role checks.
