package com.payverse.common.events;

/** Kafka topic names. Naming: {@code payverse.<domain>.<event>.v<major>}; DLT = topic + {@value #DLT_SUFFIX}. */
public final class Topics {

    public static final String DLT_SUFFIX = ".dlt";

    public static final String AUTH_USER_REGISTERED = "payverse.auth.user-registered.v1";
    public static final String AUTH_NEW_DEVICE_LOGIN = "payverse.auth.new-device-login.v1";
    public static final String AUTH_PIN_LOCKED = "payverse.auth.pin-locked.v1";

    public static final String USER_KYC_UPGRADED = "payverse.user.kyc-upgraded.v1";
    public static final String USER_BANK_ACCOUNT_LINKED = "payverse.user.bank-account-linked.v1";

    public static final String WALLET_CREATED = "payverse.wallet.created.v1";
    public static final String WALLET_MONEY_ADDED = "payverse.wallet.money-added.v1";
    public static final String WALLET_MONEY_WITHDRAWN = "payverse.wallet.money-withdrawn.v1";
    public static final String WALLET_STATUS_CHANGED = "payverse.wallet.status-changed.v1";
    public static final String WALLET_LEDGER_POSTED = "payverse.wallet.ledger-posted.v1";

    public static final String TRANSACTION_INITIATED = "payverse.transaction.initiated.v1";
    public static final String TRANSACTION_COMPLETED = "payverse.transaction.completed.v1";
    public static final String TRANSACTION_FAILED = "payverse.transaction.failed.v1";
    public static final String TRANSACTION_REVERSED = "payverse.transaction.reversed.v1";
    public static final String TRANSACTION_MONEY_REQUESTED = "payverse.transaction.money-requested.v1";

    public static final String PAYMENT_COLLECT_REQUESTED = "payverse.payment.collect-requested.v1";
    public static final String PAYMENT_PG_CHARGE_SETTLED = "payverse.payment.pg-charge-settled.v1";

    public static final String BILL_PAYMENT_COMPLETED = "payverse.bill.payment-completed.v1";
    public static final String BILL_PAYMENT_FAILED = "payverse.bill.payment-failed.v1";
    public static final String BILL_MANDATE_EXECUTED = "payverse.bill.mandate-executed.v1";

    public static final String REWARDS_CASHBACK_GRANTED = "payverse.rewards.cashback-granted.v1";

    public static final String FRAUD_TRANSACTION_FLAGGED = "payverse.fraud.transaction-flagged.v1";

    public static final String ADMIN_REFUND_APPROVED = "payverse.admin.refund-approved.v1";

    public static final String RECON_MISMATCH_DETECTED = "payverse.recon.mismatch-detected.v1";

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
