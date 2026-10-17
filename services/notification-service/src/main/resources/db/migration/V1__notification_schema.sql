-- notification-service: templates, notifications (inbox + delivery state) and captured dead letters.

CREATE TABLE templates (
    id          UUID PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL,
    channel     VARCHAR(10)  NOT NULL CHECK (channel IN ('SMS', 'EMAIL', 'PUSH')),
    subject     VARCHAR(150),
    body        VARCHAR(1000) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_templates_code_channel UNIQUE (code, channel)
);

CREATE TABLE notifications (
    id               UUID PRIMARY KEY,
    user_id          UUID          NOT NULL,
    event_id         UUID          NOT NULL,
    template_code    VARCHAR(40)   NOT NULL,
    channel          VARCHAR(10)   NOT NULL,
    subject          VARCHAR(150),
    body             VARCHAR(1000) NOT NULL,
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(300),
    next_attempt_at  TIMESTAMPTZ,
    provider_ref     VARCHAR(80),
    sent_at          TIMESTAMPTZ,
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    -- one notification per event, recipient, template and channel (redeliveries are no-ops)
    CONSTRAINT uq_notifications_once UNIQUE (event_id, user_id, template_code, channel)
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_due ON notifications (next_attempt_at) WHERE status = 'PENDING';

-- Kafka messages that exhausted their retries anywhere in the platform (<topic>.dlt)
CREATE TABLE dead_letter_events (
    id                 UUID PRIMARY KEY,
    dlt_topic          VARCHAR(200) NOT NULL,
    original_topic     VARCHAR(200) NOT NULL,
    partition_no       INT          NOT NULL,
    offset_no          BIGINT       NOT NULL,
    message_key        VARCHAR(200),
    payload            TEXT         NOT NULL,
    error_message      VARCHAR(1000),
    received_at        TIMESTAMPTZ  NOT NULL,
    replayed_at        TIMESTAMPTZ,
    CONSTRAINT uq_dead_letter_position UNIQUE (dlt_topic, partition_no, offset_no)
);
CREATE INDEX idx_dead_letters_received ON dead_letter_events (received_at DESC);

INSERT INTO templates (id, code, channel, subject, body, active, updated_at, version) VALUES
    (gen_random_uuid(), 'WELCOME', 'SMS', NULL, 'Welcome to PayVerse! Complete your KYC to start sending and receiving money.', TRUE, now(), 0),
    (gen_random_uuid(), 'WELCOME', 'EMAIL', 'Welcome to PayVerse', 'Hi! Your PayVerse account is ready. Complete minimum KYC in the app to add money, pay and get paid.', TRUE, now(), 0),
    (gen_random_uuid(), 'NEW_DEVICE_LOGIN', 'SMS', NULL, 'New PayVerse login on {{deviceName}}. Not you? Revoke the device in Settings and reset your PIN.', TRUE, now(), 0),
    (gen_random_uuid(), 'NEW_DEVICE_LOGIN', 'PUSH', 'New device login', '{{deviceName}} just signed in to your account.', TRUE, now(), 0),
    (gen_random_uuid(), 'PIN_LOCKED', 'SMS', NULL, 'Your PayVerse PIN is locked after too many wrong attempts until {{lockedUntil}}. Reset it with OTP if this wasn''t you.', TRUE, now(), 0),
    (gen_random_uuid(), 'KYC_UPGRADED', 'PUSH', 'KYC upgraded', 'Your KYC level is now {{tier}}. New wallet limits apply.', TRUE, now(), 0),
    (gen_random_uuid(), 'KYC_UPGRADED', 'EMAIL', 'Your KYC is now {{tier}}', 'Good news: your PayVerse KYC level is now {{tier}}.', TRUE, now(), 0),
    (gen_random_uuid(), 'BANK_ACCOUNT_LINKED', 'SMS', NULL, 'Bank account {{account}} ({{ifsc}}) was linked to your PayVerse wallet.', TRUE, now(), 0),
    (gen_random_uuid(), 'MONEY_ADDED', 'PUSH', 'Money added', 'INR {{amount}} added to your wallet.', TRUE, now(), 0),
    (gen_random_uuid(), 'MONEY_WITHDRAWN', 'SMS', NULL, 'INR {{amount}} is on its way to your bank account. Ref {{reference}}.', TRUE, now(), 0),
    (gen_random_uuid(), 'MONEY_WITHDRAWN', 'PUSH', 'Withdrawal started', 'INR {{amount}} is being sent to your bank.', TRUE, now(), 0),
    (gen_random_uuid(), 'WALLET_FROZEN', 'SMS', NULL, 'Your PayVerse wallet has been frozen: {{reason}}. Contact support.', TRUE, now(), 0),
    (gen_random_uuid(), 'WALLET_FROZEN', 'EMAIL', 'Your wallet is frozen', 'Your PayVerse wallet was frozen ({{reason}}). Please contact support.', TRUE, now(), 0),
    (gen_random_uuid(), 'WALLET_UNFROZEN', 'PUSH', 'Wallet active again', 'Your PayVerse wallet is active again.', TRUE, now(), 0),
    (gen_random_uuid(), 'PAYMENT_SENT', 'PUSH', 'Payment sent', 'INR {{amount}} sent successfully.', TRUE, now(), 0),
    (gen_random_uuid(), 'PAYMENT_RECEIVED', 'SMS', NULL, 'You received INR {{amount}} in your PayVerse wallet.', TRUE, now(), 0),
    (gen_random_uuid(), 'PAYMENT_RECEIVED', 'PUSH', 'Money received', 'INR {{amount}} received.', TRUE, now(), 0),
    (gen_random_uuid(), 'PAYMENT_FAILED', 'PUSH', 'Payment failed', 'Your payment of INR {{amount}} failed ({{reason}}). Any amount debited has been returned.', TRUE, now(), 0),
    (gen_random_uuid(), 'MONEY_REQUESTED', 'PUSH', 'Payment request', 'You have a request for INR {{amount}}{{noteSuffix}}.', TRUE, now(), 0),
    (gen_random_uuid(), 'COLLECT_REQUESTED', 'PUSH', 'UPI collect request', '{{payeeVpa}} requested INR {{amount}}. Approve or reject in the app.', TRUE, now(), 0),
    (gen_random_uuid(), 'CASHBACK_CREDITED', 'PUSH', 'Cashback!', 'INR {{amount}} cashback credited to your wallet.', TRUE, now(), 0),
    (gen_random_uuid(), 'SCRATCH_CARD_WON', 'PUSH', 'You won a scratch card', 'Scratch it before {{expiresAt}} to reveal your reward.', TRUE, now(), 0);
