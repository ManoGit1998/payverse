-- user-service schema. PII (email, KYC document numbers, account numbers, beneficiary values) is
-- AES-GCM encrypted; *_hash columns are HMAC blind indexes used for uniqueness and lookups.
CREATE TABLE profiles (
    user_id        UUID PRIMARY KEY,
    full_name      VARCHAR(100),
    email_enc      TEXT,
    date_of_birth  DATE,
    mobile_masked  VARCHAR(16),
    kyc_tier       VARCHAR(20) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE kyc_records (
    id               UUID PRIMARY KEY,
    user_id          UUID        NOT NULL REFERENCES profiles (user_id),
    tier             VARCHAR(20) NOT NULL,
    id_type          VARCHAR(20) NOT NULL,
    id_number_enc    TEXT        NOT NULL,
    id_number_hash   VARCHAR(64) NOT NULL,
    document_ref     VARCHAR(200),
    document_sha256  VARCHAR(64),
    status           VARCHAR(20) NOT NULL,
    reason           VARCHAR(300),
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_kyc_records_user ON kyc_records (user_id, created_at DESC);
-- one verified identity document can belong to only one account
CREATE UNIQUE INDEX uq_kyc_verified_document ON kyc_records (id_number_hash) WHERE status = 'VERIFIED';

CREATE TABLE bank_accounts (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES profiles (user_id),
    account_number_enc  TEXT         NOT NULL,
    account_hash        VARCHAR(64)  NOT NULL,
    ifsc                VARCHAR(11)  NOT NULL,
    holder_name         VARCHAR(100) NOT NULL,
    is_primary          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL,
    removed_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_bank_accounts_active ON bank_accounts (user_id, account_hash) WHERE removed_at IS NULL;
CREATE UNIQUE INDEX uq_bank_accounts_primary ON bank_accounts (user_id) WHERE is_primary AND removed_at IS NULL;

CREATE TABLE beneficiaries (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES profiles (user_id),
    type        VARCHAR(20) NOT NULL,
    value_enc   TEXT        NOT NULL,
    value_hash  VARCHAR(64) NOT NULL,
    ifsc        VARCHAR(11),
    nickname    VARCHAR(50),
    created_at  TIMESTAMPTZ NOT NULL,
    removed_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_beneficiaries_active ON beneficiaries (user_id, type, value_hash) WHERE removed_at IS NULL;
