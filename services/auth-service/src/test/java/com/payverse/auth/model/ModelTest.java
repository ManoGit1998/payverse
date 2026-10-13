package com.payverse.auth.model;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.common.error.PayverseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelTest {

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "+919876543210", "919876543210", "09876543210", "98765 43210", "+91-98765-43210"})
    void mobileNumbersAreNormalised(String raw) {
        assertThat(MobileNumber.parse(raw).value()).isEqualTo("9876543210");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "98765", "abcdefghij", "98765432101", ""})
    void invalidMobileNumbersAreRejected(String raw) {
        assertThatThrownBy(() -> MobileNumber.parse(raw))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_INVALID_MOBILE));
    }

    @Test
    void mobileIsMaskedWhenPrinted() {
        assertThatThrownBy(() -> MobileNumber.parse(null)).isInstanceOf(PayverseException.class);
        assertThat(new MobileNumber("9876543210")).hasToString("******3210");
    }

    @Test
    void deviceIdFormat() {
        assertThat(new DeviceId("device-1234").value()).isEqualTo("device-1234");
        assertThatThrownBy(() -> new DeviceId("short")).isInstanceOf(PayverseException.class);
        assertThatThrownBy(() -> new DeviceId("bad device id!")).isInstanceOf(PayverseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "4321", "0000", "123456", "654321", "111111", "1212", "2580"})
    void weakPinsAreRejectedWhenChosen(String pin) {
        assertThatThrownBy(() -> Pin.choose(pin))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_PIN_TOO_WEAK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "12345", "1234567", "12a4", ""})
    void badPinFormatIsRejected(String pin) {
        assertThatThrownBy(() -> new Pin(pin))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_PIN_INVALID_FORMAT));
    }

    @Test
    void goodPinIsAcceptedAndNeverPrinted() {
        assertThat(Pin.choose("4826").value()).isEqualTo("4826");
        assertThat(Pin.choose("482613")).hasToString("Pin[****]");
    }

    @Test
    void otpChallengeTracksAttemptsAndExpiry() {
        Instant now = Instant.parse("2026-10-14T10:00:00Z");
        OtpChallenge challenge = new OtpChallenge(OtpPurpose.LOGIN, "h", 0, now.plusSeconds(300));
        assertThat(challenge.isExpired(now)).isFalse();
        assertThat(challenge.isExpired(now.plusSeconds(300))).isTrue();
        OtpChallenge failed = challenge;
        for (int i = 0; i < OtpChallenge.MAX_ATTEMPTS; i++) {
            failed = failed.withFailedAttempt();
        }
        assertThat(failed.attemptsExhausted()).isTrue();
    }
}
