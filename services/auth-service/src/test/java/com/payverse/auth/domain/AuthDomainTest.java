package com.payverse.auth.domain;

import com.payverse.common.error.PayverseException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthDomainTest {

    static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    /** Deterministic test hasher: "hash:" + pin. */
    static final PinHasher HASHER = new PinHasher() {
        @Override
        public String hash(Pin pin) {
            return "hash:" + pin.value();
        }

        @Override
        public boolean matches(Pin pin, String hash) {
            return hash.equals("hash:" + pin.value());
        }
    };

    @Nested
    class MobileNumbers {

        @ParameterizedTest
        @ValueSource(strings = {"9876543210", "+919876543210", "919876543210", "09876543210", "98765 43210", "+91-98765-43210"})
        void normalisesIndianFormats(String raw) {
            assertThat(MobileNumber.parse(raw).value()).isEqualTo("9876543210");
        }

        @ParameterizedTest
        @ValueSource(strings = {"1234567890", "98765", "abcdefghij", "98765432101", ""})
        void rejectsInvalidNumbers(String raw) {
            assertThatThrownBy(() -> MobileNumber.parse(raw))
                    .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_INVALID_MOBILE));
        }

        @Test
        void nullIsRejectedAndToStringIsMasked() {
            assertThatThrownBy(() -> MobileNumber.parse(null)).isInstanceOf(PayverseException.class);
            assertThat(new MobileNumber("9876543210")).hasToString("******3210");
        }
    }

    @Nested
    class DeviceIds {

        @Test
        void validatesFormat() {
            assertThat(new DeviceId("device-1234").value()).isEqualTo("device-1234");
            assertThatThrownBy(() -> new DeviceId("short")).isInstanceOf(PayverseException.class);
            assertThatThrownBy(() -> new DeviceId("bad device id!")).isInstanceOf(PayverseException.class);
        }
    }

    @Nested
    class Pins {

        @ParameterizedTest
        @ValueSource(strings = {"1234", "4321", "0000", "123456", "654321", "111111", "1212", "2580"})
        void weakPinsAreRejectedWhenChosen(String pin) {
            assertThatThrownBy(() -> Pin.choose(pin))
                    .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_PIN_TOO_WEAK));
        }

        @ParameterizedTest
        @ValueSource(strings = {"123", "12345", "1234567", "12a4", ""})
        void badFormatIsRejected(String pin) {
            assertThatThrownBy(() -> new Pin(pin))
                    .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_PIN_INVALID_FORMAT));
        }

        @Test
        void goodPinIsAcceptedAndNeverPrinted() {
            assertThat(Pin.choose("4826").value()).isEqualTo("4826");
            assertThat(Pin.choose("482613")).hasToString("Pin[****]");
        }
    }

    @Nested
    class TransactionPins {

        @Test
        void correctPinResetsFailures() {
            TransactionPin pin = TransactionPin.create(UUID.randomUUID(), new Pin("4826"), HASHER, NOW);
            assertThat(pin.verify(new Pin("1111"), HASHER, NOW)).isEqualTo(new PinVerification.Incorrect(4));
            assertThat(pin.verify(new Pin("4826"), HASHER, NOW)).isInstanceOf(PinVerification.Valid.class);
            assertThat(pin.failedAttempts()).isZero();
        }

        @Test
        void locksAfterFiveFailuresForThirtyMinutes() {
            TransactionPin pin = TransactionPin.create(UUID.randomUUID(), new Pin("4826"), HASHER, NOW);
            for (int i = 4; i >= 1; i--) {
                assertThat(pin.verify(new Pin("1111"), HASHER, NOW)).isEqualTo(new PinVerification.Incorrect(i));
            }
            Instant until = NOW.plus(TransactionPin.LOCK_DURATION);
            assertThat(pin.verify(new Pin("1111"), HASHER, NOW)).isEqualTo(new PinVerification.Locked(until, true));
            assertThat(pin.isLocked(NOW)).isTrue();
            // even the correct PIN is refused while locked
            assertThat(pin.verify(new Pin("4826"), HASHER, NOW.plusSeconds(60))).isEqualTo(new PinVerification.Locked(until, false));
            // lock expires
            assertThat(pin.verify(new Pin("4826"), HASHER, until.plusSeconds(1))).isInstanceOf(PinVerification.Valid.class);
        }

        @Test
        void replaceClearsLock() {
            TransactionPin pin = TransactionPin.create(UUID.randomUUID(), new Pin("4826"), HASHER, NOW);
            for (int i = 0; i < TransactionPin.MAX_ATTEMPTS; i++) {
                pin.verify(new Pin("1111"), HASHER, NOW);
            }
            pin.replace(new Pin("7391"), HASHER, NOW);
            assertThat(pin.isLocked(NOW)).isFalse();
            assertThat(pin.verify(new Pin("7391"), HASHER, NOW)).isInstanceOf(PinVerification.Valid.class);
        }
    }

    @Nested
    class RefreshTokens {

        @Test
        void rotationKeepsFamilyAndMarksOldTokenRotated() {
            RefreshToken first = RefreshToken.issue(UUID.randomUUID(), new DeviceId("device-1234"), "h1", NOW, Duration.ofDays(30));
            RefreshToken second = first.rotate("h2", NOW.plusSeconds(10), Duration.ofDays(30));
            assertThat(second.familyId()).isEqualTo(first.familyId());
            assertThat(first.wasRotated()).isTrue();
            assertThat(first.isUsable(NOW)).isFalse();
            assertThat(second.isUsable(NOW.plusSeconds(10))).isTrue();
        }

        @Test
        void expiryAndRevocation() {
            RefreshToken token = RefreshToken.issue(UUID.randomUUID(), new DeviceId("device-1234"), "h", NOW, Duration.ofDays(1));
            assertThat(token.isUsable(NOW.plus(Duration.ofDays(2)))).isFalse();
            token.revoke(RefreshToken.RevokeReason.LOGOUT, NOW);
            token.revoke(RefreshToken.RevokeReason.REUSE_DETECTED, NOW.plusSeconds(1));
            assertThat(token.revokeReason()).isEqualTo(RefreshToken.RevokeReason.LOGOUT);
            assertThat(token.wasRotated()).isFalse();
        }
    }

    @Nested
    class Users {

        @Test
        void alwaysHasUserRoleAndSyncsConfiguredRoles() {
            User user = User.register(new MobileNumber("9876543210"), Set.of(), NOW);
            assertThat(user.roles()).containsExactly(Role.USER);
            assertThat(user.syncRoles(Set.of(Role.ADMIN))).isTrue();
            assertThat(user.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
            assertThat(user.syncRoles(Set.of(Role.ADMIN, Role.USER))).isFalse();
        }

        @Test
        void blockedUsersCannotAuthenticate() {
            User user = User.register(new MobileNumber("9876543210"), Set.of(Role.USER), NOW);
            user.ensureActive();
            user.block();
            assertThatThrownBy(user::ensureActive)
                    .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_USER_BLOCKED));
        }
    }

    @Nested
    class Devices {

        @Test
        void namesAreSanitisedAndRevocationIsUndoneByLogin() {
            Device device = Device.bind(UUID.randomUUID(), new DeviceId("device-1234"), "<script>Pixel 9</script>", NOW);
            assertThat(device.name()).isEqualTo("scriptPixel 9script");
            assertThat(Device.bind(UUID.randomUUID(), new DeviceId("device-1234"), " ", NOW).name()).isEqualTo("Unknown device");
            device.revoke(NOW);
            assertThat(device.isActive()).isFalse();
            device.seen("Pixel 10", NOW.plusSeconds(5));
            assertThat(device.isActive()).isTrue();
            assertThat(device.name()).isEqualTo("Pixel 10");
        }
    }

    @Nested
    class OtpChallenges {

        @Test
        void tracksAttemptsAndExpiry() {
            OtpChallenge challenge = new OtpChallenge(OtpPurpose.LOGIN, "h", 0, NOW.plusSeconds(300));
            assertThat(challenge.isExpired(NOW)).isFalse();
            assertThat(challenge.isExpired(NOW.plusSeconds(300))).isTrue();
            OtpChallenge failed = challenge;
            for (int i = 0; i < OtpChallenge.MAX_ATTEMPTS; i++) {
                failed = failed.withFailedAttempt();
            }
            assertThat(failed.attemptsExhausted()).isTrue();
        }
    }
}
