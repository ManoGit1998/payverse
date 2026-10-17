package com.payverse.payment.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.model.VpaHandle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEntityTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    Vpa vpa(String handle) {
        return Vpa.create(UUID.randomUUID(), VpaHandle.parse(handle), true, NOW);
    }

    @Test
    void defaultHandleIsDerivedFromTheUserId() {
        UUID user = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertThat(Vpa.defaultHandleFor(user).value()).isEqualTo("pv3fa85f6457@payverse");
        Vpa vpa = vpa("ravi@payverse");
        assertThat(vpa.isActive()).isTrue();
        assertThat(vpa.getVpaHandle().value()).isEqualTo("ravi@payverse");
    }

    @Test
    void collectLifecycle() {
        CollectRequest request = CollectRequest.create(vpa("payer@payverse"), vpa("payee@payverse"), Money.inr("250"), "rent", NOW);
        assertThat(request.getStatus()).isEqualTo(CollectStatus.PENDING);
        assertThat(request.getExpiresAt()).isEqualTo(NOW.plus(CollectRequest.TTL));

        UUID txn = UUID.randomUUID();
        request.recordTransfer(txn, "PENDING", NOW);
        assertThat(request.getStatus()).isEqualTo(CollectStatus.PROCESSING);
        request.settle(true, NOW);
        assertThat(request.getStatus()).isEqualTo(CollectStatus.APPROVED);
        assertThat(request.getTransactionId()).isEqualTo(txn);
        assertThatThrownBy(() -> request.reject(NOW))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYMENT_COLLECT_NOT_PENDING));
    }

    @Test
    void failedTransfersLeaveTheRequestPayableAndExpiryIsLazy() {
        CollectRequest request = CollectRequest.create(vpa("payer@payverse"), vpa("payee@payverse"), Money.inr("10"), null, NOW);
        request.recordTransfer(UUID.randomUUID(), "FAILED", NOW);
        assertThat(request.isActionable(NOW)).isTrue();
        assertThat(request.isActionable(NOW.plus(CollectRequest.TTL))).isFalse();
        assertThat(request.getStatus()).isEqualTo(CollectStatus.EXPIRED);
    }

    @Test
    void cannotCollectFromYourself() {
        UUID user = UUID.randomUUID();
        Vpa a = Vpa.create(user, VpaHandle.parse("a1@payverse"), true, NOW);
        Vpa b = Vpa.create(user, VpaHandle.parse("b1@payverse"), false, NOW);
        assertThatThrownBy(() -> CollectRequest.create(a, b, Money.inr("1"), null, NOW))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYMENT_SELF_COLLECT));
    }

    @Test
    void qrExpiryAndRecords() {
        QrCode dynamic = QrCode.issue(UUID.randomUUID(), UUID.randomUUID(), "x@payverse", QrType.DYNAMIC, Money.inr("5"), null, "upi://pay?pa=x@payverse",
                NOW.plusSeconds(60), NOW);
        assertThat(dynamic.isExpired(NOW)).isFalse();
        assertThat(dynamic.isExpired(NOW.plusSeconds(60))).isTrue();
        assertThat(dynamic.getAmountMoney()).isEqualTo(Money.inr("5"));
        QrCode stat = QrCode.issue(UUID.randomUUID(), UUID.randomUUID(), "x@payverse", QrType.STATIC, null, null, "p", null, NOW);
        assertThat(stat.isExpired(NOW.plusSeconds(999_999))).isFalse();
        assertThat(stat.getAmountMoney()).isNull();

        PgOrder order = PgOrder.record(UUID.randomUUID(), "k", Money.inr("100"), "UPI", "MOCK_PG", PgOrderStatus.SUCCEEDED, "ref", null, NOW);
        assertThat(order.getAmountMoney()).isEqualTo(Money.inr("100"));
        UpiSwitchRecord record = UpiSwitchRecord.settled("123456789012", UUID.randomUUID(), "a@payverse", "b@payverse", Money.inr("1"), NOW);
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
    }
}
