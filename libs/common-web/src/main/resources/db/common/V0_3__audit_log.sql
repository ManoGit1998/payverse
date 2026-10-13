-- Immutable audit trail for financial and admin actions. Provided by common-web.
CREATE TABLE IF NOT EXISTS audit_log (
    id              UUID PRIMARY KEY,
    actor           VARCHAR(100) NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    entity_type     VARCHAR(60)  NOT NULL,
    entity_id       VARCHAR(100) NOT NULL,
    details         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_type, entity_id, created_at);

CREATE OR REPLACE FUNCTION payverse_reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_log_append_only ON audit_log;
CREATE TRIGGER trg_audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION payverse_reject_modification();
