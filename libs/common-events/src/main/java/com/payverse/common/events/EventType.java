package com.payverse.common.events;

import com.payverse.common.events.payload.AdminEvents;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.BillEvents;
import com.payverse.common.events.payload.FraudEvents;
import com.payverse.common.events.payload.PaymentEvents;
import com.payverse.common.events.payload.ReconEvents;
import com.payverse.common.events.payload.RewardsEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.events.payload.WalletEvents;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The event catalog: logical type name, topic, aggregate type and payload class for every event. */
public enum EventType {

    USER_REGISTERED("auth.user-registered", Topics.AUTH_USER_REGISTERED, "User", AuthEvents.UserRegistered.class),
    NEW_DEVICE_LOGIN("auth.new-device-login", Topics.AUTH_NEW_DEVICE_LOGIN, "User", AuthEvents.NewDeviceLogin.class),
    PIN_LOCKED("auth.pin-locked", Topics.AUTH_PIN_LOCKED, "User", AuthEvents.PinLocked.class),

    KYC_UPGRADED("user.kyc-upgraded", Topics.USER_KYC_UPGRADED, "User", UserEvents.KycUpgraded.class),
    BANK_ACCOUNT_LINKED("user.bank-account-linked", Topics.USER_BANK_ACCOUNT_LINKED, "BankAccount", UserEvents.BankAccountLinked.class),

    WALLET_CREATED("wallet.created", Topics.WALLET_CREATED, "Wallet", WalletEvents.WalletCreated.class),
    MONEY_ADDED("wallet.money-added", Topics.WALLET_MONEY_ADDED, "Wallet", WalletEvents.MoneyAdded.class),
    MONEY_WITHDRAWN("wallet.money-withdrawn", Topics.WALLET_MONEY_WITHDRAWN, "Wallet", WalletEvents.MoneyWithdrawn.class),
    WALLET_STATUS_CHANGED("wallet.status-changed", Topics.WALLET_STATUS_CHANGED, "Wallet", WalletEvents.WalletStatusChanged.class),
    LEDGER_POSTED("wallet.ledger-posted", Topics.WALLET_LEDGER_POSTED, "Journal", WalletEvents.LedgerPosted.class),

    TRANSACTION_INITIATED("transaction.initiated", Topics.TRANSACTION_INITIATED, "Transaction", TransactionEvents.TransactionInitiated.class),
    TRANSACTION_COMPLETED("transaction.completed", Topics.TRANSACTION_COMPLETED, "Transaction", TransactionEvents.TransactionCompleted.class),
    TRANSACTION_FAILED("transaction.failed", Topics.TRANSACTION_FAILED, "Transaction", TransactionEvents.TransactionFailed.class),
    TRANSACTION_REVERSED("transaction.reversed", Topics.TRANSACTION_REVERSED, "Transaction", TransactionEvents.TransactionReversed.class),
    MONEY_REQUESTED("transaction.money-requested", Topics.TRANSACTION_MONEY_REQUESTED, "MoneyRequest", TransactionEvents.MoneyRequested.class),

    COLLECT_REQUESTED("payment.collect-requested", Topics.PAYMENT_COLLECT_REQUESTED, "CollectRequest", PaymentEvents.CollectRequested.class),
    PG_CHARGE_SETTLED("payment.pg-charge-settled", Topics.PAYMENT_PG_CHARGE_SETTLED, "PgOrder", PaymentEvents.PgChargeSettled.class),

    BILL_PAYMENT_COMPLETED("bill.payment-completed", Topics.BILL_PAYMENT_COMPLETED, "BillPayment", BillEvents.BillPaymentCompleted.class),
    BILL_PAYMENT_FAILED("bill.payment-failed", Topics.BILL_PAYMENT_FAILED, "BillPayment", BillEvents.BillPaymentFailed.class),
    MANDATE_EXECUTED("bill.mandate-executed", Topics.BILL_MANDATE_EXECUTED, "Mandate", BillEvents.MandateExecuted.class),

    CASHBACK_GRANTED("rewards.cashback-granted", Topics.REWARDS_CASHBACK_GRANTED, "Reward", RewardsEvents.CashbackGranted.class),

    TRANSACTION_FLAGGED("fraud.transaction-flagged", Topics.FRAUD_TRANSACTION_FLAGGED, "RiskAssessment", FraudEvents.TransactionFlagged.class),

    REFUND_APPROVED("admin.refund-approved", Topics.ADMIN_REFUND_APPROVED, "RefundRequest", AdminEvents.RefundApproved.class),

    MISMATCH_DETECTED("recon.mismatch-detected", Topics.RECON_MISMATCH_DETECTED, "ReconRun", ReconEvents.MismatchDetected.class);

    private static final Map<String, EventType> BY_TYPE_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(EventType::typeName, Function.identity()));
    private static final Map<String, EventType> BY_TOPIC = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(EventType::topic, Function.identity()));

    private final String typeName;
    private final String topic;
    private final String aggregateType;
    private final Class<?> payloadType;

    EventType(String typeName, String topic, String aggregateType, Class<?> payloadType) {
        this.typeName = typeName;
        this.topic = topic;
        this.aggregateType = aggregateType;
        this.payloadType = payloadType;
    }

    public String typeName() {
        return typeName;
    }

    public String topic() {
        return topic;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public Class<?> payloadType() {
        return payloadType;
    }

    /** Major schema version, taken from the topic suffix. */
    public int version() {
        return Integer.parseInt(topic.substring(topic.lastIndexOf(".v") + 2));
    }

    public static EventType fromTypeName(String typeName) {
        EventType type = BY_TYPE_NAME.get(typeName);
        if (type == null) {
            throw new IllegalArgumentException("Unknown event type: " + typeName);
        }
        return type;
    }

    public static EventType fromTopic(String topic) {
        EventType type = BY_TOPIC.get(topic);
        if (type == null) {
            throw new IllegalArgumentException("Unknown topic: " + topic);
        }
        return type;
    }
}
