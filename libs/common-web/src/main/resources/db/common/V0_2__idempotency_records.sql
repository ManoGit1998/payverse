-- Durable store for Idempotency-Key replay (Redis is the fast path). Provided by common-web.
CREATE TABLE IF NOT EXISTS idempotency_records (
    scope            VARCHAR(128) NOT NULL,
    idempotency_key  VARCHAR(64)  NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT          NOT NULL,
    response_body    JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_expires_at ON idempotency_records (expires_at);
