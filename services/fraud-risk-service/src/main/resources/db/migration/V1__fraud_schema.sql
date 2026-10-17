-- fraud-risk-service: configurable rules, one assessment per transaction, flags for review, risk signals.

CREATE TABLE risk_rules (
    id               UUID PRIMARY KEY,
    code             VARCHAR(40)   NOT NULL UNIQUE,
    description      VARCHAR(200)  NOT NULL,
    weight           INT           NOT NULL CHECK (weight BETWEEN 0 AND 100),
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    -- parameters (each rule uses the ones it needs)
    threshold_amount NUMERIC(19,2) CHECK (threshold_amount >= 0),
    threshold_count  INT           CHECK (threshold_count >= 0),
    window_seconds   INT           CHECK (window_seconds > 0),
    multiplier       NUMERIC(6,2)  CHECK (multiplier > 0),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE risk_assessments (
    id             UUID PRIMARY KEY,
    transaction_id UUID          NOT NULL UNIQUE,
    user_id        UUID          NOT NULL,
    payee_user_id  UUID,
    txn_type       VARCHAR(20)   NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    device_id      VARCHAR(100),
    score          INT           NOT NULL CHECK (score BETWEEN 0 AND 100),
    decision       VARCHAR(10)   NOT NULL CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    reasons        VARCHAR(500)  NOT NULL DEFAULT '',
    model_version  VARCHAR(40)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_risk_assessments_user ON risk_assessments (user_id, created_at DESC);

CREATE TABLE flags (
    id              UUID PRIMARY KEY,
    assessment_id   UUID         NOT NULL UNIQUE REFERENCES risk_assessments (id),
    transaction_id  UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    decision        VARCHAR(10)  NOT NULL CHECK (decision IN ('REVIEW', 'BLOCK')),
    score           INT          NOT NULL,
    reasons         VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'CONFIRMED_FRAUD', 'FALSE_POSITIVE')),
    resolved_by     VARCHAR(100),
    resolution_note VARCHAR(500),
    wallet_frozen   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_flags_status ON flags (status, created_at DESC);

-- devices that logged in to an existing account (auth.new-device-login)
CREATE TABLE known_devices (
    user_id       UUID         NOT NULL,
    device_id     VARCHAR(100) NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, device_id)
);

-- per-user signals from events
CREATE TABLE user_risk_profiles (
    user_id          UUID PRIMARY KEY,
    completed_count  BIGINT        NOT NULL DEFAULT 0,
    completed_total  NUMERIC(19,2) NOT NULL DEFAULT 0,
    completed_max    NUMERIC(19,2) NOT NULL DEFAULT 0,
    last_pin_lock_at TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ   NOT NULL
);

INSERT INTO risk_rules (id, code, description, weight, threshold_amount, threshold_count, window_seconds, multiplier) VALUES
    (gen_random_uuid(), 'VELOCITY_COUNT',  'More than 5 transfers in 10 minutes',                                     40, NULL,     5,    600,   NULL),
    (gen_random_uuid(), 'VELOCITY_AMOUNT', 'More than INR 50,000 sent in 24 hours',                                   40, 50000.00, NULL, 86400, NULL),
    (gen_random_uuid(), 'AMOUNT_ANOMALY',  'INR 1,000+ and over 5x the user''s average (needs 3+ past transfers)',    35, 1000.00,  3,    NULL,  5.00),
    (gen_random_uuid(), 'NEW_DEVICE',      'INR 2,000+ from a device first seen in the last 24 hours',                50, 2000.00,  NULL, 86400, NULL),
    (gen_random_uuid(), 'RECENT_PIN_LOCK', 'PIN was locked in the last 24 hours',                                     30, NULL,     NULL, 86400, NULL),
    (gen_random_uuid(), 'LARGE_AMOUNT',    'Single transfer of INR 50,000 or more',                                   25, 50000.00, NULL, NULL,  NULL);
