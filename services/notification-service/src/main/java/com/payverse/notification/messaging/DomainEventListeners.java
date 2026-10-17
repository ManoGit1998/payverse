package com.payverse.notification.messaging;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.BillEvents;
import com.payverse.common.events.payload.FraudEvents;
import com.payverse.common.events.payload.PaymentEvents;
import com.payverse.common.events.payload.RewardsEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.outbox.ProcessedEventGuard;
import com.payverse.notification.service.EventNotifications;
import com.payverse.notification.service.NotificationService;
import com.payverse.notification.service.NotificationService.NotificationRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/** Turns domain events into notifications (each event handled once, in one transaction). */
@Component
public class DomainEventListeners {

    static final String CONSUMER = "notification-service.notify";
    private static final String GROUP = "notification-service";

    private final EventEnvelopeCodec codec;
    private final ProcessedEventGuard guard;
    private final NotificationService notifications;

    public DomainEventListeners(EventEnvelopeCodec codec, ProcessedEventGuard guard, NotificationService notifications) {
        this.codec = codec;
        this.guard = guard;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.AUTH_USER_REGISTERED, groupId = GROUP)
    public void onUserRegistered(String message) {
        handle(message, AuthEvents.UserRegistered.class, EventNotifications::userRegistered);
    }

    @KafkaListener(topics = Topics.AUTH_NEW_DEVICE_LOGIN, groupId = GROUP)
    public void onNewDeviceLogin(String message) {
        handle(message, AuthEvents.NewDeviceLogin.class, EventNotifications::newDeviceLogin);
    }

    @KafkaListener(topics = Topics.AUTH_PIN_LOCKED, groupId = GROUP)
    public void onPinLocked(String message) {
        handle(message, AuthEvents.PinLocked.class, EventNotifications::pinLocked);
    }

    @KafkaListener(topics = Topics.USER_KYC_UPGRADED, groupId = GROUP)
    public void onKycUpgraded(String message) {
        handle(message, UserEvents.KycUpgraded.class, EventNotifications::kycUpgraded);
    }

    @KafkaListener(topics = Topics.USER_BANK_ACCOUNT_LINKED, groupId = GROUP)
    public void onBankAccountLinked(String message) {
        handle(message, UserEvents.BankAccountLinked.class, EventNotifications::bankAccountLinked);
    }

    @KafkaListener(topics = Topics.WALLET_MONEY_ADDED, groupId = GROUP)
    public void onMoneyAdded(String message) {
        handle(message, WalletEvents.MoneyAdded.class, EventNotifications::moneyAdded);
    }

    @KafkaListener(topics = Topics.WALLET_MONEY_WITHDRAWN, groupId = GROUP)
    public void onMoneyWithdrawn(String message) {
        handle(message, WalletEvents.MoneyWithdrawn.class, EventNotifications::moneyWithdrawn);
    }

    @KafkaListener(topics = Topics.WALLET_STATUS_CHANGED, groupId = GROUP)
    public void onWalletStatusChanged(String message) {
        handle(message, WalletEvents.WalletStatusChanged.class, EventNotifications::walletStatusChanged);
    }

    @KafkaListener(topics = Topics.TRANSACTION_COMPLETED, groupId = GROUP)
    public void onTransactionCompleted(String message) {
        handle(message, TransactionEvents.TransactionCompleted.class, EventNotifications::transactionCompleted);
    }

    @KafkaListener(topics = Topics.TRANSACTION_FAILED, groupId = GROUP)
    public void onTransactionFailed(String message) {
        handle(message, TransactionEvents.TransactionFailed.class, EventNotifications::transactionFailed);
    }

    @KafkaListener(topics = Topics.TRANSACTION_MONEY_REQUESTED, groupId = GROUP)
    public void onMoneyRequested(String message) {
        handle(message, TransactionEvents.MoneyRequested.class, EventNotifications::moneyRequested);
    }

    @KafkaListener(topics = Topics.PAYMENT_COLLECT_REQUESTED, groupId = GROUP)
    public void onCollectRequested(String message) {
        handle(message, PaymentEvents.CollectRequested.class, EventNotifications::collectRequested);
    }

    @KafkaListener(topics = Topics.REWARDS_CASHBACK_GRANTED, groupId = GROUP)
    public void onCashbackGranted(String message) {
        handle(message, RewardsEvents.CashbackGranted.class, EventNotifications::cashbackGranted);
    }

    @KafkaListener(topics = Topics.REWARDS_SCRATCH_CARD_ISSUED, groupId = GROUP)
    public void onScratchCardIssued(String message) {
        handle(message, RewardsEvents.ScratchCardIssued.class, EventNotifications::scratchCardIssued);
    }

    @KafkaListener(topics = Topics.BILL_PAYMENT_COMPLETED, groupId = GROUP)
    public void onBillPaid(String message) {
        handle(message, BillEvents.BillPaymentCompleted.class, EventNotifications::billPaid);
    }

    @KafkaListener(topics = Topics.BILL_PAYMENT_FAILED, groupId = GROUP)
    public void onBillFailed(String message) {
        handle(message, BillEvents.BillPaymentFailed.class, EventNotifications::billFailed);
    }

    @KafkaListener(topics = Topics.BILL_MANDATE_EXECUTED, groupId = GROUP)
    public void onMandateExecuted(String message) {
        handle(message, BillEvents.MandateExecuted.class, EventNotifications::mandateExecuted);
    }

    @KafkaListener(topics = Topics.FRAUD_TRANSACTION_FLAGGED, groupId = GROUP)
    public void onTransactionFlagged(String message) {
        handle(message, FraudEvents.TransactionFlagged.class, EventNotifications::transactionFlagged);
    }

    private <T> void handle(String message, Class<T> type, BiFunction<UUID, T, List<NotificationRequest>> mapping) {
        EventEnvelope<T> envelope = codec.decode(message, type);
        guard.handleOnce(envelope, CONSUMER,
                e -> mapping.apply(e.eventId(), e.payload()).forEach(notifications::enqueue));
    }
}
