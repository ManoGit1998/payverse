package com.payverse.bill.service;

import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.entity.Biller;
import com.payverse.bill.model.FetchedBill;
import com.payverse.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockBillerAdapterTest {

    final MockBillerAdapter adapter = new MockBillerAdapter(Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
    final Biller biller = Biller.of("CITY_POWER", BillCategory.ELECTRICITY, "City Power", "Consumer number", "^[0-9]{10,12}$", true,
            BigDecimal.ONE, new BigDecimal("200000"));

    @Test
    void billsAreDeterministicAndMasked() {
        FetchedBill bill = adapter.fetch(biller, "1234567890");
        assertThat(adapter.fetch(biller, "1234567890")).isEqualTo(bill);
        assertThat(bill.amountDue().amount()).isBetween(new BigDecimal("150.00"), new BigDecimal("4149.99"));
        assertThat(bill.dueDate()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(bill.accountRefMasked()).doesNotContain("123456");
        assertThatThrownBy(() -> adapter.fetch(biller, "1234560000")).isInstanceOf(BillerGateway.AccountNotFoundException.class);
    }

    @Test
    void payIsIdempotentWithDeclineAndTimeoutHooks() {
        UUID id = UUID.randomUUID();
        assertThat(adapter.pay(biller, "1", Money.inr("10"), id)).isEqualTo(adapter.pay(biller, "1", Money.inr("10"), id)).startsWith("MOCKBBPS-");
        assertThatThrownBy(() -> adapter.pay(biller, "1", Money.inr("10.13"), UUID.randomUUID())).isInstanceOf(BillerGateway.BillerDeclinedException.class);
        UUID slow = UUID.randomUUID();
        assertThatThrownBy(() -> adapter.pay(biller, "1", Money.inr("10.14"), slow)).isInstanceOf(BillerGateway.BillerUnavailableException.class);
        assertThat(adapter.pay(biller, "1", Money.inr("10.14"), slow)).startsWith("MOCKBBPS-");
    }
}
