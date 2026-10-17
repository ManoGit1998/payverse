-- transaction-service: transfers (orchestrated saga), money requests and split bills.

CREATE TABLE transactions (
    id                       UUID PRIMARY KEY,
    type                     VARCHAR(20)    NOT NULL,
    source                   VARCHAR(20)    NOT NULL,          -- DIRECT | MONEY_REQUEST | COLLECT
    source_id                UUID,
    payer_user_id            UUID           NOT NULL,
    payee_user_id            UUID           NOT NULL,
    payer_ref                VARCHAR(80),                      -- how the payee sees the payer (masked)
    payee_type               VARCHAR(10)    NOT NULL,          -- MOBILE | VPA | QR | USER
    payee_ref                VARCHAR(80)    NOT NULL,          -- masked mobile or VPA
    payee_name               VARCHAR(100),
    amount                   NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                 CHAR(3)        NOT NULL DEFAULT 'INR',
    note                     VARCHAR(100),
    status                   VARCHAR(10)    NOT NULL CHECK (status IN ('INITIATED', 'PENDING', 'SUCCESS', 'FAILED', 'REVERSED')),
    saga_step                VARCHAR(20)    NOT NULL,
    failure_code             VARCHAR(60),
    failure_reason           VARCHAR(200),
    debit_journal_id         UUID,
    credit_journal_id        UUID,
    compensation_journal_id  UUID,
    idempotency_key          VARCHAR(200)   NOT NULL,
    request_hash             VARCHAR(64)    NOT NULL,
    attempts                 INT            NOT NULL DEFAULT 0,
    next_attempt_at          TIMESTAMPTZ,
    created_at               TIMESTAMPTZ    NOT NULL,
    updated_at               TIMESTAMPTZ    NOT NULL,
    completed_at             TIMESTAMPTZ,
    version                  BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_transactions_idempotency UNIQUE (payer_user_id, idempotency_key)
);
CREATE INDEX idx_transactions_payer ON transactions (payer_user_id, created_at DESC);
CREATE INDEX idx_transactions_payee ON transactions (payee_user_id, created_at DESC);
CREATE INDEX idx_transactions_unfinished ON transactions (next_attempt_at, updated_at) WHERE status IN ('INITIATED', 'PENDING');

-- Every state change (the state machine is enforced in the Transaction entity)
CREATE TABLE transaction_status_history (
    id              UUID PRIMARY KEY,
    transaction_id  UUID         NOT NULL REFERENCES transactions (id),
    from_status     VARCHAR(10),
    to_status       VARCHAR(10)  NOT NULL,
    reason          VARCHAR(200),
    changed_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_status_history_txn ON transaction_status_history (transaction_id, changed_at);

CREATE TABLE split_bills (
    id               UUID PRIMARY KEY,
    creator_user_id  UUID           NOT NULL,
    total            NUMERIC(19, 2) NOT NULL CHECK (total > 0),
    creator_share    NUMERIC(19, 2) NOT NULL CHECK (creator_share >= 0),
    note             VARCHAR(100),
    created_at       TIMESTAMPTZ    NOT NULL
);

CREATE TABLE money_requests (
    id                 UUID PRIMARY KEY,
    requester_user_id  UUID           NOT NULL,
    requester_ref      VARCHAR(80),
    payer_user_id      UUID           NOT NULL,
    payer_ref          VARCHAR(80)    NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    note               VARCHAR(100),
    status             VARCHAR(10)    NOT NULL,
    expires_at         TIMESTAMPTZ    NOT NULL,
    transaction_id     UUID REFERENCES transactions (id),
    split_bill_id      UUID REFERENCES split_bills (id),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_money_requests_payer ON money_requests (payer_user_id, created_at DESC);
CREATE INDEX idx_money_requests_requester ON money_requests (requester_user_id, created_at DESC);
CREATE INDEX idx_money_requests_split ON money_requests (split_bill_id);
