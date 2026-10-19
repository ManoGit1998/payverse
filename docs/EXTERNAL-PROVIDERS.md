# External providers

Every external system PayVerse talks to sits behind a port (interface) in the owning service. Each port now has a
**real integration** with a named provider, running in one of two modes:

| Mode | What happens | Needs |
|---|---|---|
| `stub` (default) | The adapter builds the real request (URL, headers, auth, JSON body) and parses the real response format, but the HTTP transport answers with the provider's **default responses** from `src/main/resources/provider-stubs/<provider>.json`. No network, no keys. | nothing |
| `live` | The same adapter code calls the provider's API. | base URL + credentials |

Going live is configuration only: set the provider's mode to `live` (or `PROVIDERS_MODE=live` for all), its base URL
and credentials. A live provider with missing credentials refuses to start and names the missing keys.
The old simulators are still there: `<NAME>_ADAPTER=mock` (for example `PAYMENT_GATEWAY_ADAPTER=mock`) switches back.

## Providers

| Port (service) | Provider key | Adapter | Integrated provider / API | Credentials (env) | Stub test triggers |
|---|---|---|---|---|---|
| `OtpSender` (auth) | `sms` | `msg91` (`OTP_SENDER`) | MSG91 Flow API v5 — `POST /api/v5/flow`, `authkey` header, DLT template with `##otp##` | `MSG91_AUTH_KEY`, `MSG91_TEMPLATE_LOGIN`, `MSG91_TEMPLATE_PIN_RESET` | In STUB the OTP is also logged as `[MOCK SMS] …` (`make otp`); never logged in LIVE |
| `KycVerifier` (user) | `kyc` | `setu` | Setu KYC — `POST /api/verify/pan` (+ voter-id, driving-licence, passport, aadhaar), `x-client-id/x-client-secret/x-product-instance-id`, consent | `SETU_CLIENT_ID`, `SETU_CLIENT_SECRET`, `SETU_PRODUCT_INSTANCE_ID`, `SETU_BASE_URL` | number containing `9999` → FAILED. The holder name, when returned, must match |
| `DocumentStorage` (user) | `document-storage` | `s3` | Amazon S3 `PUT` object with AWS SigV4 + SSE-KMS (any S3-compatible store) | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `S3_BUCKET`, `S3_BUCKET_ENDPOINT` | — |
| `PayoutGateway` (wallet) | `payout` | `razorpayx` | RazorpayX Payouts — `POST /v1/payouts` (IMPS), Basic auth, `X-Payout-Idempotency` | `RAZORPAYX_KEY_ID`, `RAZORPAYX_KEY_SECRET`, `RAZORPAYX_ACCOUNT_NUMBER` | amount ending `.14` → `rejected` |
| `PaymentGatewayProvider` (payment) | `payment-gateway` | `razorpay` | Razorpay Orders — `POST /v1/orders`, `GET /v1/orders/{id}/payments`, Basic auth | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | amount ending `.13` → payment `failed` |
| `UpiSwitch` (payment) | `upi-switch` | `psp-bank` | Sponsor/PSP bank UPI API — validate VPA, pay (returns RRN) | `UPI_PSP_MERCHANT_ID`, `UPI_PSP_API_KEY`, `UPI_PSP_BASE_URL` | handle containing `unknown`/`invalid` → INVALID |
| `BillerGateway` (bill) | `biller` | `bbps` | BBPS biller aggregator — fetch / pay, idempotent `ref_id` | `BBPS_API_KEY`, `BBPS_AGENT_ID`, `BBPS_BASE_URL` | account ending `0000` unknown, `.13` declined, `.14` times out once |
| `NotificationProvider` SMS (notification) | `notification-sms` | `msg91` | MSG91 Flow API (generic DLT template, `##message##`) | `MSG91_AUTH_KEY`, `MSG91_TEMPLATE_NOTIFICATION` | — |
| `NotificationProvider` e-mail | `notification-email` | `sendgrid` | Twilio SendGrid v3 Mail Send — `POST /v3/mail/send`, bearer key, `X-Message-Id` | `SENDGRID_API_KEY`, `SENDGRID_FROM_EMAIL` | — |
| `NotificationProvider` push | `notification-push` | `fcm` | Firebase Cloud Messaging HTTP v1 — `POST /v1/projects/{id}/messages:send` | `FCM_PROJECT_ID`, `FCM_ACCESS_TOKEN` | — |

Per-provider overrides: `<KEY>_MODE` and `<KEY>_ADAPTER` with the key upper-cased and dashes as underscores
(`PAYMENT_GATEWAY_MODE=live`, `NOTIFICATION_EMAIL_ADAPTER=mock`). In Kubernetes put the credentials in the chart's
Secret (every key becomes an environment variable) and set `config.providersMode`.

## Before going live — what still needs a decision

These are the places where a real contract has to be confirmed; each is isolated in one class.

- **Razorpay (add money)** — Razorpay payments are completed by the customer in Razorpay Checkout. The adapter creates
  the order and reads its payments; in LIVE the web/app must open Checkout with the order id before the payments call
  (until then the charge is reported as not completed). Webhooks (`payment.captured`) are the production way to settle.
- **RazorpayX** — payouts go to a *fund account*. Register one (Contacts + Fund Accounts API) when a bank account is
  linked and store its id; `RazorpayXPayoutAdapter.fundAccountId(...)` is the single lookup to replace.
- **Setu KYC** — PAN follows Setu's PAN API; the other document paths use the same request style and must be checked
  against the products you subscribe to.
- **UPI (PSP bank)** and **BBPS** — every sponsor bank / aggregator has its own contract (sync vs callback, field
  names, biller ids). The mapping lives in `PspBankUpiSwitchAdapter` / `BbpsBillerAdapter`.
- **Notifications** — real SMS/e-mail/push need contact details. `RecipientDirectory` provides them; the default
  `UnconfiguredRecipientDirectory` returns placeholders that LIVE providers refuse. Implement it (phone from auth,
  e-mail from user profile, a push-token registry) and mark it `@Primary`.
- **FCM** — the access token is configured statically; mint it from the service-account key in production.

## How it is built

- `libs/common-web/.../provider`: `ProviderProperties` (`payverse.providers.<key>`), `ProviderClients` (builds the
  `RestClient`: JDK HTTP/1.1 client with timeouts in LIVE, `StubClientHttpRequestFactory` in STUB),
  `StubRules` + `StubTemplates` (rule matching and response templates).
- Stub rule file format: `{"rules": [{"method", "path" (regex), "when" {"body.x" | "header.X" | "query.x": regex},
  "status", "headers", "body", "times", "onceKey"}]}` — first match wins. Templates: `{{uuid}}`, `{{hex:N}}`,
  `{{digits:N}}`, `{{epoch}}`, `{{date:+D}}`, `{{body:a.b}}`, `{{query:x}}`, `{{path:N}}`, `{{hash:<expr>:min:max}}`.
- Each adapter has a unit test for STUB (default responses, test triggers) and LIVE (a WireMock server checks the exact
  request the provider would receive and the parsing of its real response shapes).
