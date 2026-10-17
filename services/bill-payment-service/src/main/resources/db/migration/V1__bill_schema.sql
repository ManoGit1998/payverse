-- bill-payment-service: billers (all MOCK), bill payments (wallet debit → biller → refund on decline), auto-pay mandates.

CREATE TABLE billers (
    id              UUID PRIMARY KEY,
    code            VARCHAR(40)   NOT NULL UNIQUE,
    category        VARCHAR(20)   NOT NULL CHECK (category IN ('MOBILE', 'ELECTRICITY', 'DTH', 'FASTAG', 'CREDIT_CARD')),
    name            VARCHAR(120)  NOT NULL,
    account_label   VARCHAR(60)   NOT NULL,
    account_pattern VARCHAR(100)  NOT NULL,
    fetch_supported BOOLEAN       NOT NULL,
    min_amount      NUMERIC(19,2) NOT NULL CHECK (min_amount > 0),
    max_amount      NUMERIC(19,2) NOT NULL CHECK (max_amount >= min_amount),
    adapter_code    VARCHAR(40)   NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE bill_payments (
    id                 UUID PRIMARY KEY,
    user_id            UUID          NOT NULL,
    biller_code        VARCHAR(40)   NOT NULL REFERENCES billers (code),
    category           VARCHAR(20)   NOT NULL,
    account_ref        TEXT          NOT NULL,          -- AES-GCM encrypted
    account_ref_masked VARCHAR(40)   NOT NULL,
    amount             NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency           CHAR(3)       NOT NULL,
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    step               VARCHAR(20)   NOT NULL CHECK (step IN ('NOT_STARTED', 'DEBITED', 'PAID', 'REFUNDING', 'REFUNDED')),
    debit_journal_id   UUID,
    refund_journal_id  UUID,
    biller_ref         VARCHAR(60),
    failure_code       VARCHAR(60),
    failure_reason     VARCHAR(300),
    source             VARCHAR(10)   NOT NULL CHECK (source IN ('DIRECT', 'MANDATE')),
    mandate_id         UUID,
    idempotency_key    VARCHAR(100)  NOT NULL,
    request_hash       VARCHAR(64)   NOT NULL,
    attempts           INT           NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    completed_at       TIMESTAMPTZ,
    version            BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_bill_payments_user ON bill_payments (user_id, created_at DESC);
CREATE INDEX idx_bill_payments_pending ON bill_payments (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE mandates (
    id                 UUID PRIMARY KEY,
    user_id            UUID          NOT NULL,
    biller_code        VARCHAR(40)   NOT NULL REFERENCES billers (code),
    account_ref        TEXT          NOT NULL,          -- AES-GCM encrypted
    account_ref_masked VARCHAR(40)   NOT NULL,
    frequency          VARCHAR(10)   NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY')),
    max_amount         NUMERIC(19,2) NOT NULL CHECK (max_amount > 0),
    fixed_amount       NUMERIC(19,2) CHECK (fixed_amount > 0 AND fixed_amount <= max_amount),
    currency           CHAR(3)       NOT NULL,
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    next_run_at        TIMESTAMPTZ   NOT NULL,
    run_count          INT           NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL,
    cancelled_at       TIMESTAMPTZ,
    version            BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_mandates_due ON mandates (next_run_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_mandates_user ON mandates (user_id);

CREATE TABLE mandate_executions (
    id              UUID PRIMARY KEY,
    mandate_id      UUID          NOT NULL REFERENCES mandates (id),
    scheduled_for   TIMESTAMPTZ   NOT NULL,
    bill_payment_id UUID REFERENCES bill_payments (id),
    amount          NUMERIC(19,2),
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    reason          VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL,
    completed_at    TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (mandate_id, scheduled_for)
);

-- MOCK billers (fictional names). Patterns are validated on fetch/pay; MockBillerAdapter simulates the biller.
INSERT INTO billers (id, code, category, name, account_label, account_pattern, fetch_supported, min_amount, max_amount, adapter_code) VALUES
    (gen_random_uuid(), 'VOLTEL_PREPAID',  'MOBILE',      'Voltel Prepaid (mock)',            'Mobile number',            '^[6-9][0-9]{9}$',                         FALSE, 10.00,  5000.00,   'MOCK'),
    (gen_random_uuid(), 'SKYCOM_POSTPAID', 'MOBILE',      'Skycom Postpaid (mock)',           'Mobile number',            '^[6-9][0-9]{9}$',                         TRUE,  1.00,   50000.00,  'MOCK'),
    (gen_random_uuid(), 'CITY_POWER',      'ELECTRICITY', 'City Power Distribution (mock)',   'Consumer number',          '^[0-9]{10,12}$',                          TRUE,  1.00,   200000.00, 'MOCK'),
    (gen_random_uuid(), 'STATE_GRID',      'ELECTRICITY', 'State Grid Electricity (mock)',    'Account ID',               '^[A-Z]{2}[0-9]{8}$',                      TRUE,  1.00,   200000.00, 'MOCK'),
    (gen_random_uuid(), 'STARVIEW_DTH',    'DTH',         'StarView DTH (mock)',              'Subscriber ID',            '^[0-9]{10}$',                             FALSE, 100.00, 10000.00,  'MOCK'),
    (gen_random_uuid(), 'HIGHWAY_TAG',     'FASTAG',      'HighwayTag FASTag (mock)',         'Vehicle number',           '^[A-Z]{2}[0-9]{2}[A-Z]{1,3}[0-9]{4}$',    FALSE, 100.00, 50000.00,  'MOCK'),
    (gen_random_uuid(), 'NOVA_CARD',       'CREDIT_CARD', 'Nova Bank Credit Card (mock)',     'Registered mobile number', '^[6-9][0-9]{9}$',                         TRUE,  1.00,   500000.00, 'MOCK');
