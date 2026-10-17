package com.payverse.notification.service;

import com.payverse.common.events.Amount;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.PaymentEvents;
import com.payverse.common.events.payload.RewardsEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.notification.service.NotificationService.NotificationRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventNotificationsTest {

    static final UUID EVENT = UUID.randomUUID();
    static final UUID U1 = UUID.randomUUID();
    static final UUID U2 = UUID.randomUUID();
    static final Amount AMOUNT = new Amount(new BigDecimal("150.5"), "INR");
    static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void authEvents() {
        assertThat(EventNotifications.userRegistered(EVENT, new AuthEvents.UserRegistered(U1, "98XXXXXX10", "REF")))
                .singleElement().satisfies(r -> assertRequest(r, U1, "WELCOME"));
        assertThat(EventNotifications.newDeviceLogin(EVENT, new AuthEvents.NewDeviceLogin(U1, "d", "Pixel 9", "1.2.3.4")).getFirst().vars())
                .containsEntry("deviceName", "Pixel 9");
        assertThat(EventNotifications.newDeviceLogin(EVENT, new AuthEvents.NewDeviceLogin(U1, "d", null, null)).getFirst().vars())
                .containsEntry("deviceName", "a new device");
        assertThat(EventNotifications.pinLocked(EVENT, new AuthEvents.PinLocked(U1, AT)).getFirst().vars())
                .containsEntry("lockedUntil", "17 Oct 2026, 15:30 IST");
    }

    @Test
    void userAndWalletEvents() {
        assertThat(EventNotifications.kycUpgraded(EVENT, new UserEvents.KycUpgraded(U1, "NONE", "MIN_KYC")).getFirst().vars())
                .containsEntry("tier", "MIN_KYC");
        assertThat(EventNotifications.bankAccountLinked(EVENT, new UserEvents.BankAccountLinked(U1, UUID.randomUUID(), "XXXX1234", "HDFC0001234"))
                .getFirst().vars()).containsEntry("account", "XXXX1234").containsEntry("ifsc", "HDFC0001234");
        assertThat(EventNotifications.moneyAdded(EVENT, new WalletEvents.MoneyAdded(UUID.randomUUID(), U1, AMOUNT, "pg", null))
                .getFirst().vars()).containsEntry("amount", "150.50");
        assertThat(EventNotifications.moneyWithdrawn(EVENT, new WalletEvents.MoneyWithdrawn(UUID.randomUUID(), U1, AMOUNT, null, null, null))
                .getFirst().vars()).containsEntry("reference", "");
        assertThat(EventNotifications.walletStatusChanged(EVENT, new WalletEvents.WalletStatusChanged(UUID.randomUUID(), U1, "FROZEN", null, "admin")))
                .singleElement().satisfies(r -> {
                    assertRequest(r, U1, "WALLET_FROZEN");
                    assertThat(r.vars()).containsEntry("reason", "compliance review");
                });
        assertThat(EventNotifications.walletStatusChanged(EVENT, new WalletEvents.WalletStatusChanged(UUID.randomUUID(), U1, "ACTIVE", "ok", "admin")))
                .singleElement().satisfies(r -> assertRequest(r, U1, "WALLET_UNFROZEN"));
    }

    @Test
    void transactionCompletedNotifiesPayerAndPayee() {
        List<NotificationRequest> requests = EventNotifications.transactionCompleted(EVENT,
                new TransactionEvents.TransactionCompleted(UUID.randomUUID(), "P2P", U1, U2, AMOUNT, AT));
        assertThat(requests).hasSize(2);
        assertRequest(requests.get(0), U1, "PAYMENT_SENT");
        assertRequest(requests.get(1), U2, "PAYMENT_RECEIVED");
        assertThat(EventNotifications.transactionCompleted(EVENT,
                new TransactionEvents.TransactionCompleted(UUID.randomUUID(), "BILL", U1, null, AMOUNT, AT))).hasSize(1);
    }

    @Test
    void otherTransactionAndPaymentEvents() {
        assertThat(EventNotifications.transactionFailed(EVENT,
                new TransactionEvents.TransactionFailed(UUID.randomUUID(), "P2P", U1, AMOUNT, "WALLET_INSUFFICIENT_BALANCE")).getFirst().vars())
                .containsEntry("reason", "insufficient balance");
        assertThat(EventNotifications.moneyRequested(EVENT,
                new TransactionEvents.MoneyRequested(UUID.randomUUID(), U2, U1, AMOUNT, "dinner", AT)).getFirst().vars())
                .containsEntry("noteSuffix", ": dinner");
        assertThat(EventNotifications.moneyRequested(EVENT,
                new TransactionEvents.MoneyRequested(UUID.randomUUID(), U2, U1, AMOUNT, " ", AT)).getFirst().vars())
                .containsEntry("noteSuffix", "");
        assertThat(EventNotifications.collectRequested(EVENT,
                new PaymentEvents.CollectRequested(UUID.randomUUID(), "a@payverse", "shop@payverse", U1, AMOUNT, AT)).getFirst().vars())
                .containsEntry("payeeVpa", "shop@payverse");
        assertThat(EventNotifications.collectRequested(EVENT,
                new PaymentEvents.CollectRequested(UUID.randomUUID(), "a@bank", "shop@payverse", null, AMOUNT, AT))).isEmpty();
    }

    @Test
    void rewardsEvents() {
        assertThat(EventNotifications.cashbackGranted(EVENT, new RewardsEvents.CashbackGranted(UUID.randomUUID(), U1, AMOUNT, null, "R")))
                .singleElement().satisfies(r -> assertRequest(r, U1, "CASHBACK_CREDITED"));
        assertThat(EventNotifications.scratchCardIssued(EVENT, new RewardsEvents.ScratchCardIssued(UUID.randomUUID(), U1, "TXN", "x", "R", AT))
                .getFirst().vars()).containsEntry("expiresAt", "17 Oct 2026, 15:30 IST");
    }

    @Test
    void helpersHandleNulls() {
        assertThat(EventNotifications.amount(null)).isEmpty();
        assertThat(EventNotifications.time(null)).isEmpty();
        assertThat(EventNotifications.reason(null)).isEqualTo("unknown error");
        assertThat(EventNotifications.reason("TIMEOUT")).isEqualTo("timeout");
    }

    private static void assertRequest(NotificationRequest r, UUID user, String template) {
        assertThat(r.eventId()).isEqualTo(EVENT);
        assertThat(r.userId()).isEqualTo(user);
        assertThat(r.templateCode()).isEqualTo(template);
    }
}
