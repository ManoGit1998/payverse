# wallet-service

Wallets backed by a **double-entry ledger**. Port **8083**, database `wallet_db`.

## Package structure

```
com.payverse.wallet
├── config      WalletConfig (Clock, RestClients, circuit breakers, scheduling), WalletProperties (tier limits)
├── controller  WalletController (users), InternalWalletController (saga steps, freeze, integrity)
├── dto         request/response records, MoneyDto ({"amount":"100.00","currency":"INR"})
├── entity      Wallet, LedgerAccount, Journal, LedgerEntry, LimitUsage + enums (SystemAccount, JournalType, …)
├── repository  Spring Data repositories (incl. integrity queries)
├── service     WalletPostingService (the only ledger writer), WalletService, AddMoneyService, WithdrawalService,
│               LimitService, LedgerQueryService, WalletLock (Redis), BalanceCache, WalletEventPublisher,
│               PaymentGateway (→ client.PaymentServiceGateway), PayoutGateway + MockPayoutAdapter
├── client      AuthClient (PIN verify), UserClient (bank accounts), PaymentServiceGateway (charges) — service tokens + circuit breakers
├── messaging   WalletEventListeners (auth.user-registered → create wallet, user.kyc-upgraded → tier)
├── mapper      WalletMapper (MapStruct)
└── exception   WalletErrorCode
```

## The ledger

| Account | Type | Purpose |
|---|---|---|
| `WALLET:<walletId>` | LIABILITY | One per wallet: what the platform owes the user |
| `PG_CLEARING` | ASSET | Money received from the payment gateway (add money) |
| `PAYOUT_CLEARING` | LIABILITY | Withdrawals owed to banks until the payout settles |
| `TRANSFER_SUSPENSE` | LIABILITY | P2P money between the saga's debit and credit steps (drains to 0) |
| `REWARDS_POOL` | EXPENSE | Cashback |
| `BILLER_SETTLEMENT` | LIABILITY | Bill payments owed to billers |
| `FEES_INCOME` | INCOME | Platform fees |

Every money movement is a **journal** with exactly balanced **entries** (one wallet entry plus one contra entry):

| Operation | Debit | Credit |
|---|---|---|
| Add money | PG_CLEARING | WALLET |
| Withdraw | WALLET | PAYOUT_CLEARING |
| P2P step 1 (debit) | WALLET(payer) | TRANSFER_SUSPENSE |
| P2P step 2 (credit) | TRANSFER_SUSPENSE | WALLET(payee) |
| Cashback | REWARDS_POOL | WALLET |
| Reversal | mirror image of the original journal | |

**The database enforces:**
- A deferred constraint trigger rejects any journal whose debits ≠ credits at commit.
- Triggers make `journals` and `ledger_entries` append-only (UPDATE/DELETE fail).
- `CHECK (balance >= 0)` on wallets.
- `UNIQUE(idempotency_key)`, so a posting applies once.
- A journal can be reversed at most once.

**Balance** = credits − debits of the wallet's account. It's materialised in `wallets.balance` in the same
transaction (each entry also stores `balance_after`). The wallet summary is cached in Redis and evicted after
commit. `GET /internal/wallets/integrity`, also run nightly at 02:30 IST, verifies that every materialised
balance equals the ledger sum and that every journal balances.

## Concurrency and idempotency

- **Per-wallet Redis lock** (`SET NX PX`), held until the transaction commits or rolls back. It's released with a
  compare-and-delete script. The wallet row is read only after the lock is held.
- **`@Version`** on the wallet as a second line of defence (a conflict returns 409 `CONCURRENT_MODIFICATION`).
- **Public endpoints** (add money, withdraw) require `Idempotency-Key`. `IdempotencyService` stores the response in
  Redis and Postgres in the same transaction as the posting.
- **Internal saga endpoints** require `Idempotency-Key`, scoped per calling service, and backed by the journals'
  unique key. A retry returns HTTP 200 with `replayed: true` and the original result.
- **`WalletConcurrencyIT` proves:**
  - 100 parallel transfers between 4 wallets (debit → credit, or compensation) leave every balance exact, the total
    conserved, the ledger equal to the stored balances, and suspense unchanged;
  - 100 parallel ₹100 debits on ₹5,000 → exactly 50 succeed, final balance ₹0;
  - 30 concurrent retries of one step → one journal.

## KYC-tier limits (configurable in `payverse.wallet.limits`)

| Tier | Max balance | Monthly load (add money) | Daily debit |
|---|---|---|---|
| NONE | 0 | 0 | 0 (complete KYC first: `WALLET_KYC_REQUIRED`) |
| MIN_KYC | ₹10,000 | ₹10,000 | ₹10,000 |
| FULL_KYC | ₹2,00,000 | ₹2,00,000 | ₹1,00,000 |

Credits check the max balance, add money also checks the monthly load, and debits check the daily limit. Periods
use the Asia/Kolkata calendar. The maximum single transaction is ₹1,00,000. Reversals ignore limits and freezes.

## Mocks

| Mock | Rule |
|---|---|
| payment-service `MockPaymentGatewayAdapter` | Add money charges go through payment-service (`POST /internal/pg/charges`, idempotent per key); **amounts ending in .13 are declined** (402) |
| `MockPayoutAdapter` | Accepts payouts; **amounts ending in .14 fail** (502) and the withdrawal rolls back |

## API

| Method | Path | Auth |
|---|---|---|
| GET | `/api/v1/wallets/me` | user |
| GET | `/api/v1/wallets/me/ledger?from&to&page&size` | user |
| POST | `/api/v1/wallets/me/add-money` (Idempotency-Key) | user |
| POST | `/api/v1/wallets/me/withdraw` (Idempotency-Key, X-Txn-Pin) | user |
| GET | `/internal/wallets/{userId}` | SERVICE |
| POST | `/internal/wallets/{userId}/debit` · `/credit` · `/reverse` (Idempotency-Key) | SERVICE |
| POST | `/internal/wallets/{userId}/freeze` · `/unfreeze` | SERVICE |
| GET | `/internal/wallets/integrity` | SERVICE |

Requests: [../../http/03-wallet.http](../../http/03-wallet.http).

## Events

**Consumes:** `auth.user-registered` (creates the wallet), `user.kyc-upgraded` (tier).

**Publishes (outbox):**
- `wallet.created`, `wallet.money-added`, `wallet.money-withdrawn`, `wallet.status-changed`
- `wallet.ledger-posted`: every journal, for reconciliation

**Audit:** every posting, reversal, freeze and tier change is written to the append-only `audit_log`.

## Error codes

`WALLET_INVALID_AMOUNT`, `WALLET_PIN_REQUIRED`, `WALLET_PAYMENT_DECLINED`, `WALLET_FROZEN`, `WALLET_KYC_REQUIRED`,
`WALLET_NOT_FOUND`, `WALLET_JOURNAL_NOT_FOUND`, `WALLET_LOCK_TIMEOUT`, `WALLET_INSUFFICIENT_BALANCE`,
`WALLET_LIMIT_EXCEEDED` (`limit`, `limitAmount`, `available`), `WALLET_INVALID_REVERSAL`, `WALLET_PAYOUT_FAILED`.
Errors from auth and user services (e.g. `AUTH_PIN_INCORRECT` with `remainingAttempts`) are passed through unchanged.
Outages return `DOWNSTREAM_UNAVAILABLE` (503).

## Run and test

```bash
make infra-up && make run-auth-service && make run-user-service && make run-wallet-service
./mvnw -pl services/wallet-service -am verify
```
