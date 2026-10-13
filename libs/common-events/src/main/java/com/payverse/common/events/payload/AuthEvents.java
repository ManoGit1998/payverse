package com.payverse.common.events.payload;

import java.time.Instant;
import java.util.UUID;

/** Events published by auth-service. Mobile numbers are always masked in events. */
public sealed interface AuthEvents {

    record UserRegistered(UUID userId, String mobileMasked, String referralCode) implements AuthEvents {
    }

    record NewDeviceLogin(UUID userId, String deviceId, String deviceName, String ipAddress) implements AuthEvents {
    }

    record PinLocked(UUID userId, Instant lockedUntil) implements AuthEvents {
    }
}
