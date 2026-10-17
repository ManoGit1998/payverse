# bill-payment-service

Mobile recharge and postpaid, electricity, DTH, FASTag and credit-card bills through **MOCK** billers,
plus scheduled auto-pay mandates. Port **8086**, database `bill_db`.

## Paying a bill

```
POST /bills/pay (Idempotency-Key, X-Txn-Pin)
  NOT_STARTED ─ wallet debit (BILL_PAYMENT → BILLER_SETTLEMENT, key <id>:debit) ─► DEBITED
  DEBITED ─ biller.pay(paymentId) ─► PAID ............................... SUCCESS + bill.payment-completed
          └ declined ─► REFUNDING ─ wallet credit (BILL_REFUND, key <id>:refund) ─► REFUNDED  FAILED + bill.payment-failed
  debit refused (balance, limits, frozen) ............................... FAILED, nothing moved
  wallet / biller unavailable ─► retry after 2^n s (recovery job every 10s, stale-after 60s)
```

- Same design as the transfer saga: no remote call inside a DB transaction, every step committed on its
  own (guarded by `@Version`), idempotent wallet keys, and the biller is idempotent per payment id.
- Idempotency: `UNIQUE(user_id, idempotency_key)` + request hash (same key, different bill → 422).
- Account numbers are PII: stored AES-GCM encrypted (`EncryptedStringConverter`) with a masked copy
  for display; responses and logs only show the masked value.
- Completed bill payments earn cashback in rewards-service (`BILL_PAY_1PCT`), and both outcomes are
  notified.

## Billers (seeded, fictional, MOCK)

| Code | Category | Account | Bill fetch |
|---|---|---|---|
| `VOLTEL_PREPAID` | MOBILE | mobile number | no (recharge INR 10–5,000) |
| `SKYCOM_POSTPAID` | MOBILE | mobile number | yes |
| `CITY_POWER`, `STATE_GRID` | ELECTRICITY | consumer number / account ID | yes |
| `STARVIEW_DTH` | DTH | subscriber ID | no |
| `HIGHWAY_TAG` | FASTAG | vehicle number | no |
| `NOVA_CARD` | CREDIT_CARD | registered mobile | yes |

`MockBillerAdapter` behind the `BillerGateway` port: bills are deterministic per account and month;
accounts ending `0000` don't exist; amounts ending `.13` are declined (→ refund); `.14` times out once.

## Auto-pay mandates

Created with the PIN (consent). The scheduler (every minute) claims each due run under a row lock and
inserts one `mandate_executions` row per run (`UNIQUE(mandate_id, scheduled_for)`), then pays it like a
normal bill with key `mandate:<id>:<run time>` — a repeated or crashed run can never pay twice.
Fetch billers pay the current bill, **skipped** if it exceeds `maxAmount`; recharge billers pay the fixed
`amount`. Monthly runs follow the IST calendar (the 31st becomes the last day of shorter months).
Failed and skipped runs publish `bill.mandate-executed` and notify the user.

## API

| Method | Path |
|---|---|
| GET | `/api/v1/billers?category`, `/api/v1/billers/{code}` |
| POST | `/api/v1/bills/fetch` |
| POST | `/api/v1/bills/pay` (Idempotency-Key, X-Txn-Pin) |
| GET | `/api/v1/bills/{id}`, `/api/v1/bills/history?category` |
| POST / GET | `/api/v1/mandates` (POST needs X-Txn-Pin) |
| DELETE | `/api/v1/mandates/{id}` |
| GET | `/api/v1/mandates/{id}/executions` |

Requests: [../../http/08-bills.http](../../http/08-bills.http).

## Run and test

```bash
make run-bill-payment-service
./mvnw -pl services/bill-payment-service -am verify
```

`BillPaymentServiceIT` (Postgres, Redis, Kafka, WireMock as wallet + auth): fetch and pay with replay,
key reuse and encrypted storage; declined → refunded; refused debit; biller timeout finished by the
recovery job; validation and PIN; mandates (fixed recharge paid once per run, bill above the limit
skipped, cancel, ownership).
