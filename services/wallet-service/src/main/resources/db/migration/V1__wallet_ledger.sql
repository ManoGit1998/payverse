-- wallet-service: wallets + double-entry ledger.
--
-- Invariants enforced by the database itself:
--   * every journal balances (sum of debits = sum of credits)  -> deferred constraint trigger
--   * journals and ledger entries are append-only               -> triggers reject UPDATE/DELETE
--   * a wallet balance can never go negative                    -> CHECK constraint
--   * a posting is applied at most once per idempotency key     -> UNIQUE(journals.idempotency_key)

CREATE TABLE ledger_accounts (
    id          UUID PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE')),
    owner_type  VARCHAR(10)  NOT NULL CHECK (owner_type IN ('USER', 'SYSTEM')),
    owner_id    UUID,
    currency    CHAR(3)      NOT NULL DEFAULT 'INR',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- System accounts (fixed ids so every environment matches)
INSERT INTO ledger_accounts (id, code, type, owner_type) VALUES
    ('00000000-0000-0000-0000-000000000001', 'PG_CLEARING',       'ASSET',     'SYSTEM'),
    ('00000000-0000-0000-0000-000000000002', 'PAYOUT_CLEARING',   'LIABILITY', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000003', 'TRANSFER_SUSPENSE', 'LIABILITY', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000004', 'REWARDS_POOL',      'EXPENSE',   'SYSTEM'),
    ('00000000-0000-0000-0000-000000000005', 'BILLER_SETTLEMENT', 'LIABILITY', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000006', 'FEES_INCOME',       'INCOME',    'SYSTEM');

CREATE TABLE wallets (
    id                 UUID PRIMARY KEY,
    user_id            UUID          NOT NULL UNIQUE,
    ledger_account_id  UUID          NOT NULL UNIQUE REFERENCES ledger_accounts (id),
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN')),
    status_reason      VARCHAR(200),
    kyc_tier           VARCHAR(20)   NOT NULL,
    balance            NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    currency           CHAR(3)       NOT NULL DEFAULT 'INR',
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE journals (
    id               UUID PRIMARY KEY,
    type             VARCHAR(30)  NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL UNIQUE,
    reference_type   VARCHAR(40),
    reference_id     VARCHAR(100),
    description      VARCHAR(200),
    reverses_journal_id UUID REFERENCES journals (id),
    created_at       TIMESTAMPTZ  NOT NULL
);
-- a journal can be reversed at most once
CREATE UNIQUE INDEX uq_journals_reverses ON journals (reverses_journal_id) WHERE reverses_journal_id IS NOT NULL;

CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY,
    journal_id     UUID           NOT NULL REFERENCES journals (id),
    account_id     UUID           NOT NULL REFERENCES ledger_accounts (id),
    side           VARCHAR(6)     NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency       CHAR(3)        NOT NULL,
    balance_after  NUMERIC(19, 2),            -- running balance, wallet (user) accounts only
    created_at     TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, created_at DESC);
CREATE INDEX idx_ledger_entries_journal ON ledger_entries (journal_id);

-- Per-period usage counters for KYC-tier limits
CREATE TABLE wallet_limit_usage (
    id            UUID PRIMARY KEY,
    wallet_id     UUID           NOT NULL REFERENCES wallets (id),
    limit_type    VARCHAR(20)    NOT NULL,
    period_start  DATE           NOT NULL,
    used          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_wallet_limit_usage UNIQUE (wallet_id, limit_type, period_start)
);

-- Double-entry: debits must equal credits for every journal (checked at commit time)
CREATE FUNCTION check_journal_balanced() RETURNS trigger AS $$
DECLARE
    debits  NUMERIC;
    credits NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE side = 'CREDIT'), 0)
      INTO debits, credits
      FROM ledger_entries WHERE journal_id = NEW.journal_id;
    IF debits <> credits OR debits = 0 THEN
        RAISE EXCEPTION 'Journal % is unbalanced (debits %, credits %)', NEW.journal_id, debits, credits;
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

-- Append-only ledger (payverse_reject_modification() comes from common-web's V0_3 migration)
CREATE TRIGGER trg_journals_append_only BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION payverse_reject_modification();
CREATE TRIGGER trg_ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION payverse_reject_modification();
