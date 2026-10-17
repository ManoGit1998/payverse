-- Phase 6: bill payments, auto-pay and blocked payments.
INSERT INTO templates (id, code, channel, subject, body, active, updated_at, version) VALUES
    (gen_random_uuid(), 'BILL_PAID', 'PUSH', 'Bill paid', 'INR {{amount}} paid to {{biller}}. Ref {{reference}}.', TRUE, now(), 0),
    (gen_random_uuid(), 'BILL_PAID', 'SMS', NULL, 'PayVerse: INR {{amount}} paid to {{biller}}. Ref {{reference}}.', TRUE, now(), 0),
    (gen_random_uuid(), 'BILL_PAYMENT_FAILED', 'PUSH', 'Bill payment failed', 'Your {{category}} payment of INR {{amount}} failed ({{reason}}). Any amount debited has been refunded.', TRUE, now(), 0),
    (gen_random_uuid(), 'AUTOPAY_FAILED', 'PUSH', 'Auto-pay did not go through', 'An auto-pay run was {{status}}. Check Auto-pay in the app.', TRUE, now(), 0),
    (gen_random_uuid(), 'AUTOPAY_FAILED', 'EMAIL', 'Auto-pay did not go through', 'One of your PayVerse auto-pay runs was {{status}}. Open Auto-pay in the app to see why.', TRUE, now(), 0),
    (gen_random_uuid(), 'PAYMENT_BLOCKED', 'SMS', NULL, 'PayVerse blocked a payment from your account for your safety. If this was you, contact support; if not, reset your PIN now.', TRUE, now(), 0),
    (gen_random_uuid(), 'PAYMENT_BLOCKED', 'PUSH', 'Payment blocked', 'We blocked a payment that looked unusual. Contact support if it was you.', TRUE, now(), 0);
