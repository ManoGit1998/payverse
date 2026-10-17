package com.payverse.payment.model;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.payment.exception.PaymentErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpiModelTest {

    @Test
    void vpaHandlesAreNormalised() {
        VpaHandle handle = VpaHandle.parse("  Ravi.K@PayVerse ");
        assertThat(handle.value()).isEqualTo("ravi.k@payverse");
        assertThat(handle.isPayverse()).isTrue();
        assertThat(VpaHandle.parse("ravi@okhdfc").isPayverse()).isFalse();
        assertThat(VpaHandle.payverse("Asha.Verma").value()).isEqualTo("asha.verma@payverse");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-at-sign", "@payverse", "a@b", "ravi@pay verse", ""})
    void invalidVpasAreRejected(String raw) {
        assertThatThrownBy(() -> VpaHandle.parse(raw))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYMENT_INVALID_VPA));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "a..b", "trailing.", "has-dash", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void invalidCustomNamesAreRejected(String name) {
        assertThatThrownBy(() -> VpaHandle.payverse(name)).isInstanceOf(PayverseException.class);
    }

    @Test
    void upiUriRoundTrips() {
        UpiPaymentUri uri = new UpiPaymentUri(VpaHandle.parse("ravi@payverse"), "Ravi Kumar", Money.inr("150.5"), "Lunch & tea", "ref-1");
        String text = uri.format();
        assertThat(text).startsWith("upi://pay?pa=ravi%40payverse&pn=Ravi%20Kumar&am=150.50&cu=INR&tn=Lunch%20%26%20tea&tr=ref-1");
        assertThat(UpiPaymentUri.parse(text)).isEqualTo(uri);
        UpiPaymentUri staticUri = UpiPaymentUri.parse("upi://pay?pa=shop@payverse");
        assertThat(staticUri.amount()).isNull();
        assertThat(staticUri.payeeName()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/pay?pa=x@payverse", "upi://pay", "upi://pay?pa=x@payverse&cu=USD&am=1",
            "upi://pay?pa=x@payverse&am=-5", "upi://pay?pa=x@payverse&am=abc", "not a uri at all %%"})
    void nonUpiPayloadsAreRejected(String payload) {
        assertThatThrownBy(() -> UpiPaymentUri.parse(payload))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isIn(
                        PaymentErrorCode.PAYMENT_INVALID_QR, PaymentErrorCode.PAYMENT_INVALID_VPA));
    }
}
