package com.payverse.bill.entity;

import com.payverse.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillEntitiesTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Biller POWER = Biller.of("CITY_POWER", BillCategory.ELECTRICITY, "City Power", "Consumer number", "^[0-9]{10,12}$", true,
            new BigDecimal("1"), new BigDecimal("200000"));
    static final Biller TAG = Biller.of("HIGHWAY_TAG", BillCategory.FASTAG, "Tag", "Vehicle number", "^[A-Z]{2}[0-9]{2}[A-Z]{1,3}[0-9]{4}$", false,
            new BigDecimal("100"), new BigDecimal("50000"));

    static BillPayment payment() {
        return BillPayment.create(UUID.randomUUID(), POWER, "1234567890", "******7890", Money.inr("500"), PaymentSource.DIRECT, null,
                "key-12345", "hash", NOW);
    }

    @Test
    void billerValidatesAccountsAndAmounts() {
        assertThat(TAG.acceptsAccount("ka 01 ab 1234")).isTrue();
        assertThat(Biller.normalize(" ka01 ab1234 ")).isEqualTo("KA01AB1234");
        assertThat(TAG.acceptsAccount("12345")).isFalse();
        assertThat(Biller.normalize(null)).isEmpty();
        assertThat(TAG.acceptsAmount(Money.inr("99.99"))).isFalse();
        assertThat(TAG.acceptsAmount(Money.inr("100"))).isTrue();
        assertThat(TAG.acceptsAmount(Money.inr("50000.01"))).isFalse();
        assertThat(TAG.isActive()).isTrue();
    }

    @Test
    void happyPath() {
        BillPayment p = payment();
        assertThat(p.isDue(NOW)).isTrue();
        p.recordDebit(UUID.randomUUID(), NOW);
        p.recordBillerSuccess("REF", NOW);
        assertThat(p.getStatus()).isEqualTo(BillPaymentStatus.SUCCESS);
        assertThat(p.getStep()).isEqualTo(BillStep.PAID);
        assertThat(p.isSettled()).isTrue();
        assertThat(p.isDue(NOW)).isFalse();
        assertThat(p.getMoney()).isEqualTo(Money.inr("500"));
        assertThatThrownBy(() -> p.recordDebit(UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void declineRefundsAndRetriesBackOff() {
        BillPayment p = payment();
        p.recordDebit(UUID.randomUUID(), NOW);
        p.scheduleRetry("x".repeat(400), NOW);
        assertThat(p.getFailureReason()).hasSize(300);
        assertThat(p.isDue(NOW)).isFalse();
        assertThat(p.isDue(NOW.plusSeconds(2))).isTrue();
        p.startRefund("BILL_BILLER_DECLINED", "declined", NOW);
        p.recordRefund(UUID.randomUUID(), NOW);
        assertThat(p.getStatus()).isEqualTo(BillPaymentStatus.FAILED);
        assertThat(p.getFailureCode()).isEqualTo("BILL_BILLER_DECLINED");

        BillPayment refused = payment();
        refused.failBeforeDebit("WALLET_INSUFFICIENT_BALANCE", "no money", NOW);
        assertThat(refused.getStep()).isEqualTo(BillStep.NOT_STARTED);
        assertThat(refused.getStatus()).isEqualTo(BillPaymentStatus.FAILED);
        assertThatThrownBy(() -> BillPayment.create(UUID.randomUUID(), POWER, "1", "1", Money.inr("0"), PaymentSource.DIRECT, null, "k", "h", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mandateSchedulesRunsInIst() {
        Mandate m = Mandate.create(UUID.randomUUID(), POWER, "1234567890", "m", MandateFrequency.MONTHLY, Money.inr("3000"), null,
                Instant.parse("2026-01-31T04:30:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(m.isDue(Instant.parse("2026-01-31T04:29:59Z"))).isFalse();
        assertThat(m.claimRun()).isEqualTo(Instant.parse("2026-01-31T04:30:00Z"));
        assertThat(m.getNextRunAt()).isEqualTo(Instant.parse("2026-02-28T04:30:00Z")); // last day of February
        assertThat(m.getRunCount()).isEqualTo(1);
        assertThat(MandateFrequency.WEEKLY.next(NOW)).isEqualTo(NOW.plusSeconds(7 * 86400));
        m.cancel(NOW);
        assertThat(m.getStatus()).isEqualTo(MandateStatus.CANCELLED);
        assertThat(m.isDue(Instant.MAX)).isFalse();
        assertThatThrownBy(m::claimRun).isInstanceOf(IllegalStateException.class);

        Mandate past = Mandate.create(UUID.randomUUID(), TAG, "KA01AB1234", "m", MandateFrequency.WEEKLY, Money.inr("500"), Money.inr("300"),
                NOW.minusSeconds(100), NOW);
        assertThat(past.getNextRunAt()).isEqualTo(NOW);
        assertThat(past.getFixedMoney()).isEqualTo(Money.inr("300"));
        assertThatThrownBy(() -> Mandate.create(UUID.randomUUID(), TAG, "x", "m", MandateFrequency.WEEKLY, Money.inr("500"), null, NOW, NOW))
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> Mandate.create(UUID.randomUUID(), TAG, "x", "m", MandateFrequency.WEEKLY, Money.inr("500"), Money.inr("600"), NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Mandate.create(UUID.randomUUID(), POWER, "x", "m", MandateFrequency.WEEKLY, Money.inr("0"), null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionCompletesOnce() {
        MandateExecution e = MandateExecution.start(UUID.randomUUID(), NOW, NOW);
        e.attach(UUID.randomUUID(), Money.inr("10"));
        assertThatThrownBy(() -> e.complete(ExecutionStatus.PENDING, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(e.complete(ExecutionStatus.SUCCESS, null, NOW)).isTrue();
        assertThat(e.complete(ExecutionStatus.FAILED, "x".repeat(300), NOW)).isFalse();
        assertThat(e.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }
}
