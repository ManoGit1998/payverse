-- Transactional outbox + consumer de-duplication. Provided by common-outbox.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY,
    topic           VARCHAR(200) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    published_at    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS processed_events (
    event_id      UUID         NOT NULL,
    consumer      VARCHAR(100) NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
