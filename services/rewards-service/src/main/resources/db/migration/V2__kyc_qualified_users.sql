-- Users who reached MIN_KYC or FULL_KYC. Kafka does not order user-registered and kyc-upgraded (different
-- topics), so a referral created after the KYC event must still find the qualification and pay out.
CREATE TABLE kyc_qualified_users (
    user_id      UUID PRIMARY KEY,
    tier         VARCHAR(10) NOT NULL,
    qualified_at TIMESTAMPTZ NOT NULL
);
