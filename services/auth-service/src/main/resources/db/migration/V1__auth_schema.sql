-- auth-service schema. Mobile numbers are AES-GCM encrypted; lookups use an HMAC blind index.
CREATE TABLE users (
    id           UUID PRIMARY KEY,
    mobile_enc   TEXT         NOT NULL,
    mobile_hash  VARCHAR(64)     NOT NULL UNIQUE,
    status       VARCHAR(20)  NOT NULL,
    roles        VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE devices (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id),
    device_id     VARCHAR(128) NOT NULL,
    name          VARCHAR(100),
    bound_at      TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT uq_devices_user_device UNIQUE (user_id, device_id)
);

-- Opaque refresh tokens: only SHA-256 hashes are stored. family_id groups a rotation chain.
CREATE TABLE refresh_tokens (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users (id),
    device_id      VARCHAR(128) NOT NULL,
    token_hash     VARCHAR(64)     NOT NULL UNIQUE,
    family_id      UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoke_reason  VARCHAR(30)
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user_device ON refresh_tokens (user_id, device_id) WHERE revoked_at IS NULL;

-- Argon2id hashed transaction PINs with lockout state.
CREATE TABLE transaction_pins (
    user_id          UUID PRIMARY KEY REFERENCES users (id),
    pin_hash         TEXT        NOT NULL,
    failed_attempts  INT         NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0
);

-- RS256 signing keys; private keys AES-GCM encrypted. Published in the JWKS while ACTIVE or recently retired.
CREATE TABLE signing_keys (
    kid              VARCHAR(64) PRIMARY KEY,
    public_jwk       TEXT        NOT NULL,
    private_key_enc  TEXT        NOT NULL,
    status           VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    retired_at       TIMESTAMPTZ
);
