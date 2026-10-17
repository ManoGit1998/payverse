# rewards-service

Cashback rules engine, scratch cards, coupons and referral bonuses. Port **8087**, database `rewards_db`.
Rewards are paid from wallet-service's `REWARDS_POOL` ledger account (purpose `CASHBACK`).

## Flow

```
transaction.completed (P2P) ─┐
wallet.money-added ──────────┼─▶ RuleEngine ─▶ scratch card (amount hidden)  ─ scratch ─┐
bill.payment-completed ──────┘   first matching rule  └▶ reward PENDING_CREDIT ◀────────┤
coupon redeem / referral (MIN_KYC) ──────────────────────▶ reward PENDING_CREDIT ◀───────┘
                                                                  │ RewardCreditService (every 1s)
                                                                  ▼
                                    wallet POST /internal/wallets/{user}/credit  Idempotency-Key reward:<id>
                                                                  ▼
                                    CREDITED + rewards.cashback-granted (outbox) ─▶ notification-service
transaction.reversed ─▶ ClawbackService: void unscratched cards / pending rewards, reverse credited
                        journals (reward:<id>:clawback), give the budget back
```

## Rules engine

A rule has a **trigger** (`P2P_TRANSFER`, `ADD_MONEY`, `BILL_PAYMENT`), a **reward type** and conditions:

| Field | Meaning |
|---|---|
| `rewardType` | `SCRATCH_CARD` (random whole rupees in `[minReward, maxReward]`), `FLAT` (`flatAmount`), `PERCENT` (`percent` of the amount, capped at `maxReward`) |
| `minTxnAmount` / `maxTxnAmount` | Amount range of the qualifying event |
| `firstTimeOnly` | Only the user's first event of this trigger (counted in `user_activity`) |
| `dailyCapPerUser` | Rewards (or cards) per user per IST day |
| `activeFrom` / `activeTo` | Validity window |
| `budget` | Total that may be paid; reserved atomically when the reward/card is created, given back on expiry, void and clawback |
| `priority` | Higher first. **The first rule that matches and has budget wins — one reward per event** |

Seeded rules:

| Code | Rule |
|---|---|
| `P2P_SCRATCH` | Scratch card worth INR 1–25 for P2P payments of INR 100+, max 3 a day |
| `FIRST_ADD_MONEY` | INR 25 on the first add-money of INR 500+ |
| `BILL_PAY_1PCT` | 1% on bill payments of INR 200+, max INR 50 (bill payments arrive in Phase 6) |

Coupon `PAYVERSE10` (INR 10, 10,000 uses) is seeded too.

## Design notes

- **No remote calls inside DB transactions for credits.** Rewards are written as PENDING_CREDIT
  together with the event that earned them; `RewardCreditService` calls the wallet afterwards with
  `reward:<id>`. A crash or a second instance can only repeat the call, which the wallet replays.
  Wallet rejections (e.g. a limit) back off 5s, 10s, 20s, … and end in FAILED after 8 attempts.
- **Scratch and redeem don't take an Idempotency-Key:** scratching twice returns the same card, and
  a coupon is redeemable once per user (`UNIQUE(coupon_id, user_id)`). They only create a reward;
  the money moves with the reward's own key.
- **Coupon limits under concurrency:** the coupon row is locked (`PESSIMISTIC_WRITE`) during redemption.
- **Referrals:** every user gets an 8-character code (no look-alike characters) on `user-registered`
  (or lazily); a `referralCode` entered at sign-up links the referee. The first `kyc-upgraded` to
  MIN_KYC/FULL_KYC pays the referrer INR 100 and the referee INR 50 (configurable).
- **Clawback** (`transaction.reversed`): unscratched cards → VOIDED, uncredited rewards → VOIDED,
  credited rewards → the cashback journal is reversed on the wallet. If the user has already spent
  it, the reward becomes CLAWBACK_FAILED (left to reconciliation, Phase 7).
- `first-time-only` counts the events rewards-service has seen since it started consuming.

## API

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/rewards/me` | user: totals + recent rewards |
| GET | `/api/v1/rewards/history` | user |
| GET | `/api/v1/rewards/scratch-cards` | user (amount `null` until scratched) |
| POST | `/api/v1/rewards/scratch-cards/{id}/scratch` | user |
| GET | `/api/v1/rewards/coupons` | user: coupons still redeemable |
| POST | `/api/v1/rewards/coupons/{code}/redeem` | user |
| GET | `/api/v1/rewards/referral` | user: code and stats |
| GET / POST | `/api/v1/admin/cashback-rules` | ADMIN, SUPPORT read; ADMIN write (audited) |
| PUT | `/api/v1/admin/cashback-rules/{code}` | ADMIN (spent budget is kept) |
| GET / POST | `/api/v1/admin/coupons` | ADMIN, SUPPORT read; ADMIN write |
| PUT | `/api/v1/admin/coupons/{code}/status` | ADMIN |

Requests: [../../http/07-rewards.http](../../http/07-rewards.http). Swagger: http://localhost:8087/swagger-ui.html

## Run and test

```bash
make run-rewards-service
./mvnw -pl services/rewards-service -am verify
```

`RewardsServiceIT` (Postgres, Redis, Kafka, WireMock as wallet) covers scratch cards with the
daily cap and a redelivered event, the credit call and its idempotency key, first-time add money,
wallet rejections with back-off, coupons (limit, once per user, admin), referrals paid on KYC,
clawback and rule administration.

## Configuration

| Env var / property | Default |
|---|---|
| `SERVER_PORT` | `8087` |
| `WALLET_SERVICE_URL` | `http://localhost:8083` |
| `payverse.rewards.scratch-card-validity` | `30d` |
| `payverse.rewards.referrer-bonus` / `referee-bonus` | `100.00` / `50.00` |
| `payverse.rewards.max-credit-attempts` | `8` |
| `SERVICE_CLIENT_SECRET`, `POSTGRES_*`, `REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS`, `AUTH_SERVICE_URL` | as in the other services |
