package com.payverse.payment.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.payment.entity.Vpa;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.model.VpaHandle;
import com.payverse.payment.repository.VpaRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentServiceUnitTest {

    @Test
    void mockSwitchResolvesOnlyPayverseHandlesAndRrnsAreStable() {
        VpaRepository repository = mock(VpaRepository.class);
        UUID user = UUID.randomUUID();
        when(repository.findByHandle("ravi@payverse")).thenReturn(Optional.of(Vpa.create(user, VpaHandle.parse("ravi@payverse"), true, Instant.now())));
        when(repository.findByHandle("ghost@payverse")).thenReturn(Optional.empty());
        MockUpiSwitchAdapter upiSwitch = new MockUpiSwitchAdapter(repository);

        assertThat(upiSwitch.resolve(VpaHandle.parse("ravi@payverse"))).isEqualTo(user);
        assertThatThrownBy(() -> upiSwitch.resolve(VpaHandle.parse("ghost@payverse")))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VPA_NOT_FOUND));
        assertThatThrownBy(() -> upiSwitch.resolve(VpaHandle.parse("ravi@okhdfc")))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VPA_EXTERNAL_UNSUPPORTED));

        UUID txn = UUID.randomUUID();
        String rrn = upiSwitch.settle(txn, VpaHandle.parse("payer1@payverse"), VpaHandle.parse("payee1@payverse"), Money.inr("1"));
        assertThat(rrn).hasSize(12).containsOnlyDigits();
        assertThat(upiSwitch.settle(txn, VpaHandle.parse("payer1@payverse"), VpaHandle.parse("payee1@payverse"), Money.inr("1"))).isEqualTo(rrn);
    }

    @Test
    void mockGatewayDeclinesPointThirteen() {
        MockPaymentGatewayAdapter pg = new MockPaymentGatewayAdapter();
        assertThat(pg.name()).isEqualTo("MOCK_PG");
        assertThat(pg.charge(Money.inr("100.13"), "CARD", "k")).isInstanceOf(PaymentGatewayProvider.Charge.Declined.class);
        var approved = (PaymentGatewayProvider.Charge.Approved) pg.charge(Money.inr("100.00"), "UPI", "k");
        assertThat(approved.reference()).startsWith("MOCKPG-")
                .isEqualTo(((PaymentGatewayProvider.Charge.Approved) pg.charge(Money.inr("100.00"), "UPI", "k")).reference());
    }

    @Test
    void renderedQrIsAPng() {
        byte[] png = QrService.render("upi://pay?pa=ravi@payverse&am=10.00&cu=INR");
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }
}
