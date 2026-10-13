package com.payverse.auth.entity;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.DeviceId;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.PinVerification;
import com.payverse.common.error.PayverseException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityTest {

    static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    @Test
    void userAlwaysHasUserRoleAndSyncsConfiguredRoles() {
        User user = User.register(new MobileNumber("9876543210"), "hash", Set.of(), NOW);
        assertThat(user.getRoleSet()).containsExactly(Role.USER);
        assertThat(user.getMobileNumber().masked()).isEqualTo("******3210");
        assertThat(user.syncRoles(Set.of(Role.ADMIN))).isTrue();
        assertThat(user.getRoleSet()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        assertThat(user.syncRoles(Set.of(Role.ADMIN, Role.USER))).isFalse();
    }

    @Test
    void blockedUsersCannotAuthenticate() {
        User user = User.register(new MobileNumber("9876543210"), "hash", Set.of(Role.USER), NOW);
        user.ensureActive();
        user.block();
        assertThat(user.isActive()).isFalse();
        assertThatThrownBy(user::ensureActive)
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(AuthErrorCode.AUTH_USER_BLOCKED));
    }

    @Test
    void deviceNamesAreSanitisedAndLoginReactivates() {
        Device device = Device.bind(UUID.randomUUID(), new DeviceId("device-1234"), "<script>Pixel 9</script>", NOW);
        assertThat(device.getName()).isEqualTo("scriptPixel 9script");
        assertThat(Device.sanitizeName(" ")).isEqualTo("Unknown device");
        device.revoke(NOW);
        assertThat(device.isActive()).isFalse();
        device.seen("Pixel 10", NOW.plusSeconds(5));
        assertThat(device.isActive()).isTrue();
        assertThat(device.getName()).isEqualTo("Pixel 10");
    }

    @Test
    void refreshTokenRotationKeepsFamily() {
        RefreshToken first = RefreshToken.issue(UUID.randomUUID(), new DeviceId("device-1234"), "h1", NOW, Duration.ofDays(30));
        RefreshToken second = first.rotate("h2", NOW.plusSeconds(10), Duration.ofDays(30));
        assertThat(second.getFamilyId()).isEqualTo(first.getFamilyId());
        assertThat(first.wasRotated()).isTrue();
        assertThat(first.isUsable(NOW)).isFalse();
        assertThat(second.isUsable(NOW.plusSeconds(10))).isTrue();
        assertThat(second.isBoundTo(new DeviceId("device-1234"))).isTrue();
        assertThat(second.isBoundTo(new DeviceId("device-other"))).isFalse();
    }

    @Test
    void refreshTokenExpiryAndFirstRevocationWins() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), new DeviceId("device-1234"), "h", NOW, Duration.ofDays(1));
        assertThat(token.isUsable(NOW.plus(Duration.ofDays(2)))).isFalse();
        token.revoke(RevokeReason.LOGOUT, NOW);
        token.revoke(RevokeReason.REUSE_DETECTED, NOW.plusSeconds(1));
        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);
        assertThat(token.wasRotated()).isFalse();
    }

    @Test
    void pinLocksAfterFiveFailuresForThirtyMinutes() {
        TransactionPin pin = TransactionPin.create(UUID.randomUUID(), "hash", NOW);
        for (int remaining = 4; remaining >= 1; remaining--) {
            assertThat(pin.recordAttempt(false, NOW)).isEqualTo(new PinVerification.Incorrect(remaining));
        }
        Instant until = NOW.plus(TransactionPin.LOCK_DURATION);
        assertThat(pin.recordAttempt(false, NOW)).isEqualTo(new PinVerification.Locked(until, true));
        assertThat(pin.isLocked(NOW)).isTrue();
        assertThat(pin.recordAttempt(true, NOW.plusSeconds(60))).isEqualTo(new PinVerification.Locked(until, false));
        assertThat(pin.recordAttempt(true, until.plusSeconds(1))).isInstanceOf(PinVerification.Valid.class);
    }

    @Test
    void correctPinResetsFailuresAndReplaceClearsLock() {
        TransactionPin pin = TransactionPin.create(UUID.randomUUID(), "hash", NOW);
        pin.recordAttempt(false, NOW);
        assertThat(pin.recordAttempt(true, NOW)).isInstanceOf(PinVerification.Valid.class);
        assertThat(pin.getFailedAttempts()).isZero();
        for (int i = 0; i < TransactionPin.MAX_ATTEMPTS; i++) {
            pin.recordAttempt(false, NOW);
        }
        pin.replace("new-hash", NOW);
        assertThat(pin.isLocked(NOW)).isFalse();
        assertThat(pin.getPinHash()).isEqualTo("new-hash");
    }

    @Test
    void signingKeyRetires() {
        SigningKey key = SigningKey.active("kid", "{}", "{}", NOW);
        assertThat(key.isActive()).isTrue();
        key.retire(NOW);
        assertThat(key.isActive()).isFalse();
        assertThat(key.getRetiredAt()).isEqualTo(NOW);
    }
}
