# PayVerse web

React + TypeScript single-page app for the PayVerse gateway API: the customer wallet and, for ADMIN /
SUPPORT accounts, the back-office console. Built with Vite, React Router 7 and TanStack Query; plain CSS
with design tokens (light + dark).

```bash
make web-install      # npm ci
make web-dev          # http://localhost:5173 — proxies /api to the gateway (GATEWAY_URL, default http://localhost:8080)
make web-test         # Vitest + Testing Library (API mocked; no backend needed)
make web-build        # type-check + production build into web/dist
```

The app only talks to the **API gateway** (`/api/v1/**`); in development Vite proxies `/api`, so there is no
CORS to configure. Start the backend first (`make e2e` leaves the whole stack running in Docker, or
`make run-*`). Sign in with any Indian mobile number and read the OTP with `make otp` (MOCK SMS). Mobiles in
`ADMIN_MOBILES` / `SUPPORT_MOBILES` (`.env`) also see the **Back office** section.

## What's in it

| Area | Screens (API reference: `http/*.http`) |
|---|---|
| Sign-in | OTP login/sign-up with referral code, PIN reset, onboarding (set PIN → minimum KYC) |
| Home | balance, KYC limits, quick actions, pending requests, recent activity, cashback |
| Pay | UPI ID (payee name resolved first), mobile, QR (paste a `upi://` link or upload a QR image), saved contacts |
| Requests | money requests and UPI collect requests, in and out: pay/approve (PIN), decline, cancel, create |
| Wallet | add money (mock PG), withdraw to a linked bank (PIN), ledger with running balance |
| History | filters, details with status timeline, raise a dispute, CSV/PDF statements |
| Bills | billers by category, fetch + pay (refunds shown), history, auto-pay mandates and their runs |
| Rewards | cashback, scratch cards (reveal), coupons, referral code + invite link, history |
| Profile | details, KYC (min/full with document upload), bank accounts, beneficiaries, UPI IDs, static/dynamic QR, PIN change, devices |
| Back office | users (search, freeze), disputes, maker-checker refunds, risk flags + rule tuning, reconciliation runs/mismatches, cashback rules + coupons, templates + dead-letter replay, audit log |

## How it talks to the API (`src/api`)

- **Tokens:** the access token stays in memory; the refresh token in `localStorage` so a reload keeps you
  signed in (a production deployment would put it in an HttpOnly cookie behind a BFF). On a 401 the client
  rotates the refresh token **once** for all concurrent requests and retries; if a parallel request already
  rotated it, it simply retries with the new token. A rejected refresh signs you out.
- **Device:** one `deviceId` per browser (sessions and the fraud new-device rule are bound to it).
- **Money moves:** an `Idempotency-Key` per distinct payload (`useIdempotencyKey`): retrying the same payment
  after a timeout reuses the key, so the server replays the result instead of paying twice; changing a field
  makes a new key. Mutations are never auto-retried. The PIN (`X-Txn-Pin`) is asked in a modal per action
  and never stored.
- **Errors:** RFC 9457 problems become `ApiError` (code, detail, correlation ID, extras such as
  `remainingAttempts` or the limit left) and friendly messages (`errorMessage`).
- **Files:** QR PNGs and statements are fetched with the bearer token and shown/downloaded as object URLs.
- Pending transfers (202) are polled until they settle. Pages are lazy-loaded, so the back office is not in
  the customer bundle (initial JS ~96 kB gzipped).

## Deploying

`npm run build` produces static files in `web/dist`. Serve them from any static host or CDN and route
`/api/**` to the gateway on the same origin, or set `CORS_ALLOWED_ORIGINS` on the gateway to the app's origin.
