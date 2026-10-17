# reconciliation-service

Spring Batch reconciliation of the wallet ledger against the payment provider and the UPI switch.
Port **8090**, database `recon_db` (recon tables + Spring Batch metadata, both from Flyway).

## Jobs

| Job | LEFT (system of record) | RIGHT | Key | Mismatch types |
|---|---|---|---|---|
| `PG_VS_LEDGER` | payment-service PG charges (SUCCEEDED) | wallet ADD_MONEY journals | provider reference | `CHARGED_NOT_CREDITED`, `CREDITED_WITHOUT_CHARGE`, `AMOUNT_MISMATCH` |
| `TRANSFERS_VS_UPI` | transaction-service SUCCESS/REVERSED transfers | payment-service UPI switch records | transaction id | `MISSING_SWITCH_RECORD`, `ORPHAN_SWITCH_RECORD`, `AMOUNT_MISMATCH` |
| `WALLET_INTEGRITY` | wallet-service integrity report | — | wallet / journal | `WALLET_OUT_OF_BALANCE`, `UNBALANCED_JOURNAL`, `SUSPENSE_NOT_ZERO` |

`CHARGED_NOT_CREDITED` is the "charged at the gateway but the wallet commit failed" case noted in
Phase 3 — the mismatch is the signal to refund it.

Each comparison run is a Spring Batch job built for that run:

1. **extract** (tasklet) — page through both feeds (`/internal/...`, role SERVICE) into `recon_records`.
   LEFT is read for the business day exactly ([00:00, 24:00) IST); RIGHT 15 minutes wider on both ends
   (`payverse.recon.tolerance`) so a credit posted just after midnight still matches.
2. **compare** (chunk, 200) — a JDBC cursor over LEFT ⟗ RIGHT by reference → `MismatchClassifier` →
   `recon_mismatches` + `recon.mismatch-detected` (outbox, same chunk transaction). A RIGHT-only record
   counts only if it falls inside the day (otherwise it belongs to the neighbouring day's run).
3. **summarize** — counts (left, right, matched, mismatches), run COMPLETED.

A failing step marks the run FAILED with the error. Runs: nightly at 02:30 IST for the previous day
(all jobs, `payverse.recon.nightly-cron`), or on demand. Mismatches stay OPEN until an ADMIN resolves
them with a note (audited).

## API

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/admin/recon/jobs/{job}/run?date=YYYY-MM-DD` (default today IST) → 202 RUNNING | ADMIN |
| GET | `/api/v1/admin/recon/runs?job`, `/runs/{id}`, `/runs/{id}/mismatches` | ADMIN, SUPPORT |
| GET | `/api/v1/admin/recon/mismatches` (open) | ADMIN, SUPPORT |
| POST | `/api/v1/admin/recon/mismatches/{id}/resolve` | ADMIN |

Requests: [../../http/10-recon.http](../../http/10-recon.http).

## Run and test

```bash
make run-reconciliation-service
./mvnw -pl services/reconciliation-service -am verify
```

`ReconciliationServiceIT` (Postgres incl. Batch tables, Redis, Kafka, WireMock feeds): injected
CHARGED_NOT_CREDITED / AMOUNT_MISMATCH (across midnight) / CREDITED_WITHOUT_CHARGE, a next-day record that
must not count, outbox events, Batch execution COMPLETED, resolve rules; multi-page feeds; integrity job;
a failing feed marks the run FAILED; role checks.
