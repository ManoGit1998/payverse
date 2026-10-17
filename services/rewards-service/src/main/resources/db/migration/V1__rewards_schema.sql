-- rewards-service: cashback rules, rewards (the rewards ledger), scratch cards, coupons, referrals.

CREATE TABLE cashback_rules (
    id                 UUID PRIMARY KEY,
    code               VARCHAR(40)   NOT NULL UNIQUE,
    name               VARCHAR(120)  NOT NULL,
    trigger_type       VARCHAR(20)   NOT NULL CHECK (trigger_type IN ('P2P_TRANSFER', 'ADD_MONEY', 'BILL_PAYMENT')),
    reward_type        VARCHAR(20)   NOT NULL CHECK (reward_type IN ('SCRATCH_CARD', 'FLAT', 'PERCENT')),
    flat_amount        NUMERIC(19,2) CHECK (flat_amount > 0),
    percent            NUMERIC(5,2)  CHECK (percent > 0 AND percent <= 100),
    min_reward         NUMERIC(19,2) CHECK (min_reward > 0),
    max_reward         NUMERIC(19,2) CHECK (max_reward > 0),
    -- conditions
    min_txn_amount     NUMERIC(19,2),
    max_txn_amount     NUMERIC(19,2),
    first_time_only    BOOLEAN       NOT NULL DEFAULT FALSE,
    daily_cap_per_user INT           CHECK (daily_cap_per_user > 0),
    active_from        TIMESTAMPTZ,
    active_to          TIMESTAMPTZ,
    budget             NUMERIC(19,2) CHECK (budget >= 0),
    spent              NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (spent >= 0),
    priority           INT           NOT NULL DEFAULT 0,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    currency           CHAR(3)       NOT NULL DEFAULT 'INR',
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CHECK (budget IS NULL OR spent <= budget),
    CHECK (reward_type <> 'FLAT' OR flat_amount IS NOT NULL),
    CHECK (reward_type <> 'PERCENT' OR percent IS NOT NULL),
    CHECK (reward_type <> 'SCRATCH_CARD' OR (min_reward IS NOT NULL AND max_reward IS NOT NULL AND min_reward <= max_reward))
);
CREATE INDEX idx_cashback_rules_trigger ON cashback_rules (trigger_type, active, priority DESC);

-- every money reward a user earns; credited to the wallet (REWARDS_POOL → user) by RewardCreditJob
CREATE TABLE rewards (
    id              UUID PRIMARY KEY,
    user_id         UUID          NOT NULL,
    rule_code       VARCHAR(40)   NOT NULL,
    source_type     VARCHAR(20)   NOT NULL CHECK (source_type IN ('TRANSACTION', 'ADD_MONEY', 'BILL_PAYMENT', 'SCRATCH_CARD', 'COUPON', 'REFERRAL')),
    source_id       VARCHAR(100)  NOT NULL,
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3)       NOT NULL,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING_CREDIT', 'CREDITED', 'FAILED', 'CLAWED_BACK', 'CLAWBACK_FAILED', 'VOIDED')),
    journal_id      UUID,
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(300),
    next_attempt_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    credited_at     TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (user_id, rule_code, source_type, source_id)
);
CREATE INDEX idx_rewards_due ON rewards (next_attempt_at) WHERE status = 'PENDING_CREDIT';
CREATE INDEX idx_rewards_user ON rewards (user_id, created_at DESC);
CREATE INDEX idx_rewards_source ON rewards (source_id);

CREATE TABLE scratch_cards (
    id          UUID PRIMARY KEY,
    user_id     UUID          NOT NULL,
    rule_code   VARCHAR(40)   NOT NULL,
    source_type VARCHAR(20)   NOT NULL,
    source_id   VARCHAR(100)  NOT NULL,
    amount      NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency    CHAR(3)       NOT NULL,
    status      VARCHAR(20)   NOT NULL CHECK (status IN ('UNSCRATCHED', 'SCRATCHED', 'EXPIRED', 'VOIDED')),
    expires_at  TIMESTAMPTZ   NOT NULL,
    scratched_at TIMESTAMPTZ,
    reward_id   UUID REFERENCES rewards (id),
    created_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (user_id, rule_code, source_type, source_id)
);
CREATE INDEX idx_scratch_cards_user ON scratch_cards (user_id, created_at DESC);
CREATE INDEX idx_scratch_cards_expiry ON scratch_cards (expires_at) WHERE status = 'UNSCRATCHED';
CREATE INDEX idx_scratch_cards_source ON scratch_cards (source_id);

-- how many qualifying events of each trigger a user has had (for first-time-only rules)
CREATE TABLE user_activity (
    user_id      UUID        NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    event_count  BIGINT      NOT NULL,
    PRIMARY KEY (user_id, trigger_type)
);

CREATE TABLE coupons (
    id            UUID PRIMARY KEY,
    code          VARCHAR(32)   NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{4,32}$'),
    description   VARCHAR(200)  NOT NULL,
    cashback      NUMERIC(19,2) NOT NULL CHECK (cashback > 0),
    currency      CHAR(3)       NOT NULL DEFAULT 'INR',
    valid_from    TIMESTAMPTZ   NOT NULL,
    valid_until   TIMESTAMPTZ   NOT NULL,
    usage_limit   INT           NOT NULL CHECK (usage_limit > 0),
    used_count    INT           NOT NULL DEFAULT 0,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CHECK (used_count <= usage_limit),
    CHECK (valid_from < valid_until)
);

CREATE TABLE coupon_redemptions (
    id          UUID PRIMARY KEY,
    coupon_id   UUID        NOT NULL REFERENCES coupons (id),
    user_id     UUID        NOT NULL,
    reward_id   UUID        NOT NULL REFERENCES rewards (id),
    redeemed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (coupon_id, user_id)
);

CREATE TABLE referral_codes (
    user_id    UUID PRIMARY KEY,
    code       VARCHAR(12) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE referrals (
    id                 UUID PRIMARY KEY,
    referrer_id        UUID        NOT NULL,
    referee_id         UUID        NOT NULL UNIQUE,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'REWARDED')),
    referrer_reward_id UUID REFERENCES rewards (id),
    referee_reward_id  UUID REFERENCES rewards (id),
    created_at         TIMESTAMPTZ NOT NULL,
    rewarded_at        TIMESTAMPTZ,
    version            BIGINT      NOT NULL DEFAULT 0,
    CHECK (referrer_id <> referee_id)
);
CREATE INDEX idx_referrals_referrer ON referrals (referrer_id);

-- seed rules and a launch coupon
INSERT INTO cashback_rules (id, code, name, trigger_type, reward_type, min_reward, max_reward, min_txn_amount,
                            daily_cap_per_user, budget, priority)
VALUES (gen_random_uuid(), 'P2P_SCRATCH', 'Scratch card on P2P payments of INR 100+ (up to 3 a day)', 'P2P_TRANSFER',
        'SCRATCH_CARD', 1.00, 25.00, 100.00, 3, 100000.00, 10);
INSERT INTO cashback_rules (id, code, name, trigger_type, reward_type, flat_amount, min_txn_amount, first_time_only,
                            budget, priority)
VALUES (gen_random_uuid(), 'FIRST_ADD_MONEY', 'INR 25 cashback on the first add-money of INR 500+', 'ADD_MONEY',
        'FLAT', 25.00, 500.00, TRUE, 50000.00, 20);
INSERT INTO cashback_rules (id, code, name, trigger_type, reward_type, percent, max_reward, min_txn_amount, budget, priority)
VALUES (gen_random_uuid(), 'BILL_PAY_1PCT', '1% cashback on bill payments of INR 200+ (max INR 50)', 'BILL_PAYMENT',
        'PERCENT', 1.00, 50.00, 200.00, 50000.00, 10);
INSERT INTO coupons (id, code, description, cashback, valid_from, valid_until, usage_limit)
VALUES (gen_random_uuid(), 'PAYVERSE10', 'INR 10 cashback for trying PayVerse', 10.00, now(), now() + INTERVAL '365 days', 10000);
