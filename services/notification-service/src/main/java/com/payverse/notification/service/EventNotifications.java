package com.payverse.notification.service;

import com.payverse.common.events.Amount;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.BillEvents;
import com.payverse.common.events.payload.FraudEvents;
import com.payverse.common.events.payload.PaymentEvents;
import com.payverse.common.events.payload.RewardsEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.notification.service.NotificationService.NotificationRequest;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Which template(s) each domain event triggers, and the template variables. Only already-masked or
 * non-sensitive data reaches a template (masked account numbers, device names, amounts).
 */
public final class EventNotifications {

    static final DateTimeFormatter IST = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));

    private EventNotifications() {
    }

    public static List<NotificationRequest> userRegistered(UUID eventId, AuthEvents.UserRegistered e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "WELCOME", Map.of()));
    }

    public static List<NotificationRequest> newDeviceLogin(UUID eventId, AuthEvents.NewDeviceLogin e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "NEW_DEVICE_LOGIN",
                Map.of("deviceName", e.deviceName() == null ? "a new device" : e.deviceName())));
    }

    public static List<NotificationRequest> pinLocked(UUID eventId, AuthEvents.PinLocked e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "PIN_LOCKED", Map.of("lockedUntil", time(e.lockedUntil()))));
    }

    public static List<NotificationRequest> kycUpgraded(UUID eventId, UserEvents.KycUpgraded e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "KYC_UPGRADED", Map.of("tier", e.toTier())));
    }

    public static List<NotificationRequest> bankAccountLinked(UUID eventId, UserEvents.BankAccountLinked e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "BANK_ACCOUNT_LINKED",
                Map.of("account", e.maskedAccountNumber(), "ifsc", e.ifsc())));
    }

    public static List<NotificationRequest> moneyAdded(UUID eventId, WalletEvents.MoneyAdded e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "MONEY_ADDED", Map.of("amount", amount(e.amount()))));
    }

    public static List<NotificationRequest> moneyWithdrawn(UUID eventId, WalletEvents.MoneyWithdrawn e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "MONEY_WITHDRAWN",
                Map.of("amount", amount(e.amount()), "reference", nullToEmpty(e.payoutReference()))));
    }

    public static List<NotificationRequest> walletStatusChanged(UUID eventId, WalletEvents.WalletStatusChanged e) {
        String template = "FROZEN".equals(e.status()) ? "WALLET_FROZEN" : "WALLET_UNFROZEN";
        return List.of(new NotificationRequest(eventId, e.userId(), template,
                Map.of("reason", e.reason() == null ? "compliance review" : e.reason())));
    }

    /** The payer hears "sent", the payee hears "received". */
    public static List<NotificationRequest> transactionCompleted(UUID eventId, TransactionEvents.TransactionCompleted e) {
        Map<String, String> vars = Map.of("amount", amount(e.amount()));
        if (e.payeeUserId() == null) {
            return List.of(new NotificationRequest(eventId, e.payerUserId(), "PAYMENT_SENT", vars));
        }
        return List.of(new NotificationRequest(eventId, e.payerUserId(), "PAYMENT_SENT", vars),
                new NotificationRequest(eventId, e.payeeUserId(), "PAYMENT_RECEIVED", vars));
    }

    public static List<NotificationRequest> transactionFailed(UUID eventId, TransactionEvents.TransactionFailed e) {
        return List.of(new NotificationRequest(eventId, e.payerUserId(), "PAYMENT_FAILED",
                Map.of("amount", amount(e.amount()), "reason", reason(e.reasonCode()))));
    }

    public static List<NotificationRequest> moneyRequested(UUID eventId, TransactionEvents.MoneyRequested e) {
        String note = e.note() == null || e.note().isBlank() ? "" : ": " + e.note();
        return List.of(new NotificationRequest(eventId, e.payerUserId(), "MONEY_REQUESTED",
                Map.of("amount", amount(e.amount()), "noteSuffix", note)));
    }

    public static List<NotificationRequest> collectRequested(UUID eventId, PaymentEvents.CollectRequested e) {
        if (e.payerUserId() == null) {
            return List.of();
        }
        return List.of(new NotificationRequest(eventId, e.payerUserId(), "COLLECT_REQUESTED",
                Map.of("amount", amount(e.amount()), "payeeVpa", e.payeeVpa())));
    }

    public static List<NotificationRequest> cashbackGranted(UUID eventId, RewardsEvents.CashbackGranted e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "CASHBACK_CREDITED", Map.of("amount", amount(e.amount()))));
    }

    public static List<NotificationRequest> scratchCardIssued(UUID eventId, RewardsEvents.ScratchCardIssued e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "SCRATCH_CARD_WON", Map.of("expiresAt", time(e.expiresAt()))));
    }

    public static List<NotificationRequest> billPaid(UUID eventId, BillEvents.BillPaymentCompleted e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "BILL_PAID", Map.of("amount", amount(e.amount()),
                "biller", nullToEmpty(e.billerName()), "reference", nullToEmpty(e.billerReference()))));
    }

    public static List<NotificationRequest> billFailed(UUID eventId, BillEvents.BillPaymentFailed e) {
        return List.of(new NotificationRequest(eventId, e.userId(), "BILL_PAYMENT_FAILED", Map.of("amount", amount(e.amount()),
                "category", e.category() == null ? "bill" : e.category().toLowerCase(Locale.ROOT).replace('_', ' '),
                "reason", reason(e.reasonCode()))));
    }

    /** Successful runs are covered by BILL_PAID; only failed or skipped runs need attention. */
    public static List<NotificationRequest> mandateExecuted(UUID eventId, BillEvents.MandateExecuted e) {
        if ("SUCCESS".equals(e.status()) || "PENDING".equals(e.status())) {
            return List.of();
        }
        return List.of(new NotificationRequest(eventId, e.userId(), "AUTOPAY_FAILED", Map.of("status", e.status().toLowerCase(Locale.ROOT))));
    }

    /** Users hear about blocks only; REVIEW flags are an internal matter. */
    public static List<NotificationRequest> transactionFlagged(UUID eventId, FraudEvents.TransactionFlagged e) {
        if (!"BLOCK".equals(e.decision())) {
            return List.of();
        }
        return List.of(new NotificationRequest(eventId, e.userId(), "PAYMENT_BLOCKED", Map.of()));
    }

    static String amount(Amount amount) {
        return amount == null ? "" : amount.value().setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    static String time(Instant instant) {
        return instant == null ? "" : IST.format(instant);
    }

    /** WALLET_INSUFFICIENT_BALANCE → "insufficient balance". */
    static String reason(String code) {
        if (code == null || code.isBlank()) {
            return "unknown error";
        }
        String text = code.replaceFirst("^[A-Z]+_", "").replace('_', ' ').toLowerCase(Locale.ROOT);
        return text.isBlank() ? code.toLowerCase(Locale.ROOT) : text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
