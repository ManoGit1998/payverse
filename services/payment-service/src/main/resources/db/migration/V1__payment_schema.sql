-- payment-service: simulated UPI layer + payment-gateway records.

CREATE TABLE vpas (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL,
    handle      VARCHAR(80)  NOT NULL UNIQUE,      -- full VPA, e.g. asha.verma@payverse
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_vpas_user ON vpas (user_id);
CREATE UNIQUE INDEX uq_vpas_default ON vpas (user_id) WHERE is_default;

CREATE TABLE collect_requests (
    id              UUID PRIMARY KEY,
    payer_vpa       VARCHAR(80)    NOT NULL,
    payee_vpa       VARCHAR(80)    NOT NULL,
    payer_user_id   UUID           NOT NULL,
    payee_user_id   UUID           NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3)        NOT NULL DEFAULT 'INR',
    note            VARCHAR(100),
    status          VARCHAR(20)    NOT NULL,
    expires_at      TIMESTAMPTZ    NOT NULL,
    transaction_id  UUID,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_collect_payer ON collect_requests (payer_user_id, created_at DESC);
CREATE INDEX idx_collect_payee ON collect_requests (payee_user_id, created_at DESC);

CREATE TABLE qr_codes (
    id          UUID PRIMARY KEY,
    user_id     UUID           NOT NULL,
    vpa         VARCHAR(80)    NOT NULL,
    type        VARCHAR(10)    NOT NULL CHECK (type IN ('STATIC', 'DYNAMIC')),
    amount      NUMERIC(19, 2),
    note        VARCHAR(100),
    payload     VARCHAR(500)   NOT NULL,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_qr_user ON qr_codes (user_id, created_at DESC);

-- Charges through the (MOCK) payment gateway for "add money"
CREATE TABLE pg_orders (
    id                UUID PRIMARY KEY,
    user_id           UUID           NOT NULL,
    idempotency_key   VARCHAR(200)   NOT NULL UNIQUE,
    amount            NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency          CHAR(3)        NOT NULL DEFAULT 'INR',
    method            VARCHAR(20)    NOT NULL,
    provider          VARCHAR(30)    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    provider_ref      VARCHAR(60),
    failure_reason    VARCHAR(200),
    created_at        TIMESTAMPTZ    NOT NULL
);

-- What the (MOCK) UPI switch settled: the "provider side" that reconciliation compares with the ledger
CREATE TABLE upi_switch_records (
    id              UUID PRIMARY KEY,
    rrn             VARCHAR(20)    NOT NULL UNIQUE,
    transaction_id  UUID           NOT NULL UNIQUE,
    payer_vpa       VARCHAR(80),
    payee_vpa       VARCHAR(80),
    amount          NUMERIC(19, 2) NOT NULL,
    currency        CHAR(3)        NOT NULL DEFAULT 'INR',
    status          VARCHAR(20)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL
);
