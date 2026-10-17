-- Phase 6: the device a transfer was made from (for fraud's new-device rule) and the risk result.
ALTER TABLE transactions
    ADD COLUMN device_id     VARCHAR(100),
    ADD COLUMN risk_score    INT CHECK (risk_score BETWEEN 0 AND 100),
    ADD COLUMN risk_decision VARCHAR(10) CHECK (risk_decision IN ('ALLOW', 'REVIEW', 'BLOCK'));
