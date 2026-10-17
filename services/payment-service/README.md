# payment-service

The **simulated UPI layer** and the payment-gateway integration. Port **8085**, database `payment_db`.

## Package structure

```
com.payverse.payment
├── config      PaymentConfig (RestClients, circuit breakers), PaymentProperties
├── controller  VpaController, CollectRequestController, QrController, InternalPaymentController
├── dto         request/response records
├── entity      Vpa, CollectRequest, QrCode, PgOrder, UpiSwitchRecord + enums
├── repository  Spring Data repositories
├── service     VpaService, QrService (ZXing), CollectRequestService, PaymentGatewayService, SettlementService,
│               PaymentEventPublisher, UpiSwitch + MockUpiSwitchAdapter, PaymentGatewayProvider + MockPaymentGatewayAdapter
├── client      TransactionClient (collect approval → transfer), UserClient (payee names)
├── messaging   PaymentEventListeners
├── model       VpaHandle, UpiPaymentUri (upi://pay?pa=…&am=…)
├── mapper      PaymentMapper (MapStruct)
└── exception   PaymentErrorCode
```

## Features

| Feature | Details |
|---|---|
| UPI IDs | A default `pv<10 hex>@payverse` is created on `auth.user-registered`. Up to 3 per user; custom `name@payverse` (3–30 chars of a-z, 0-9 and dots). Resolution shows the payee name |
| **MOCK UPI switch** | `MockUpiSwitchAdapter` behind `UpiSwitch`. Only `@payverse` IDs exist; other PSPs (`@okhdfc`, `@ybl`, …) give `PAYMENT_VPA_EXTERNAL_UNSUPPORTED`. Each completed transfer (`transaction.completed`) is recorded as a settlement with a 12-digit RRN in `upi_switch_records`, the provider-side data reconciliation compares with the ledger in Phase 7 |
| QR codes | Static (VPA only, one per user) and dynamic (fixed amount, 1–60 min validity), encoded as NPCI `upi://pay?pa=…&pn=…&am=…&cu=INR&tn=…&tr=<qrId>`. PNG rendered with ZXing. Decoding works from the payload text or an uploaded image, checks PayVerse-issued QRs against our records (expiry: 410 `PAYMENT_QR_EXPIRED`), and rejects non-UPI payloads |
| Collect requests | The payee asks a payer VPA for money. The payer approves with their PIN, which calls transaction-service `/internal/transfers` with key `collect:<id>`, so approving twice can't pay twice. Rejection, 24h expiry, PROCESSING → APPROVED/FAILED from transaction events |
| **MOCK payment gateway** | `POST /internal/pg/charges`, called by wallet-service's add money. `MockPaymentGatewayAdapter` approves instantly except **amounts ending in .13**. Idempotent per key (`pg_orders.idempotency_key`). Publishes `payment.pg-charge-settled` |

## API

| Method | Path | Auth |
|---|---|---|
| POST / GET | `/api/v1/vpa`, `/api/v1/vpa/me` | user |
| GET | `/api/v1/vpa/{vpa}/resolve` | user (no internal ids exposed) |
| POST | `/api/v1/qr/static`, `/api/v1/qr/dynamic` | user |
| GET | `/api/v1/qr/{id}.png` | owner |
| POST | `/api/v1/qr/decode`, `/api/v1/qr/decode-image` (multipart `image`) | user |
| POST / GET | `/api/v1/collect-requests` (`direction=INCOMING\|OUTGOING`) | user |
| POST | `/api/v1/collect-requests/{id}/approve` (Idempotency-Key + X-Txn-Pin), `/reject` | payer |
| GET | `/internal/vpa/{vpa}`, `/internal/vpa/by-user/{userId}` | SERVICE |
| POST | `/internal/qr/decode`, `/internal/pg/charges` | SERVICE |

## Events

**Consumes:**
- `auth.user-registered`: creates the default VPA.
- `transaction.completed`: records the switch settlement and settles collects.
- `transaction.failed`: settles collects.

**Publishes:** `payment.collect-requested`, `payment.pg-charge-settled`.

Requests: [../../http/05-payments.http](../../http/05-payments.http).
