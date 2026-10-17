-- Phase 7: refunds of completed transfers (approved in admin-service, maker-checker).
-- A refund reverses the payee's credit journal, then the payer's debit journal; then SUCCESS → REVERSED.
ALTER TABLE transactions
    ADD COLUMN refund_id               UUID UNIQUE,
    ADD COLUMN refund_status           VARCHAR(10) CHECK (refund_status IN ('PENDING', 'COMPLETED', 'FAILED')),
    ADD COLUMN refund_reason           VARCHAR(200),
    ADD COLUMN refund_failure_code     VARCHAR(60),
    ADD COLUMN refund_payee_journal_id UUID,
    ADD COLUMN refund_payer_journal_id UUID,
    ADD COLUMN refunded_at             TIMESTAMPTZ;
CREATE INDEX idx_transactions_completed_at ON transactions (completed_at) WHERE status IN ('SUCCESS', 'REVERSED');
