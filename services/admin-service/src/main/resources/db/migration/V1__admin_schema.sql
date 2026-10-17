-- admin-service: disputes and maker-checker refunds. Admin actions are written to audit_log (db/common V0_3).

CREATE TABLE disputes (
    id              UUID PRIMARY KEY,
    transaction_id  UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED')),
    outcome         VARCHAR(10)  CHECK (outcome IN ('REFUND', 'REJECTED')),
    assignee        VARCHAR(100),
    resolution_note VARCHAR(500),
    refund_id       UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CHECK ((status = 'RESOLVED') = (outcome IS NOT NULL))
);
-- one unresolved dispute per transaction and user
CREATE UNIQUE INDEX uq_disputes_open ON disputes (transaction_id, user_id) WHERE status <> 'RESOLVED';
CREATE INDEX idx_disputes_status ON disputes (status, created_at);
CREATE INDEX idx_disputes_user ON disputes (user_id, created_at DESC);

CREATE TABLE refund_requests (
    id              UUID PRIMARY KEY,
    transaction_id  UUID          NOT NULL,
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3)       NOT NULL,
    reason          VARCHAR(200)  NOT NULL,
    dispute_id      UUID REFERENCES disputes (id),
    requested_by    VARCHAR(100)  NOT NULL,
    decided_by      VARCHAR(100),
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REJECTED')),
    failure_code    VARCHAR(60),
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    decided_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    -- maker-checker: the approver is never the requester
    CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
-- at most one live refund per transaction
CREATE UNIQUE INDEX uq_refunds_live ON refund_requests (transaction_id) WHERE status IN ('REQUESTED', 'APPROVED', 'PROCESSING');
CREATE INDEX idx_refunds_status ON refund_requests (status, created_at);
