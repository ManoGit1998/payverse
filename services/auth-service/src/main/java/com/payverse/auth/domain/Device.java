package com.payverse.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A device bound to a user on successful OTP login. */
public final class Device {

    private final UUID id;
    private final UUID userId;
    private final DeviceId deviceId;
    private String name;
    private final Instant boundAt;
    private Instant lastSeenAt;
    private Instant revokedAt;

    private Device(UUID id, UUID userId, DeviceId deviceId, String name, Instant boundAt, Instant lastSeenAt, Instant revokedAt) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.deviceId = Objects.requireNonNull(deviceId);
        this.name = name;
        this.boundAt = Objects.requireNonNull(boundAt);
        this.lastSeenAt = lastSeenAt;
        this.revokedAt = revokedAt;
    }

    public static Device bind(UUID userId, DeviceId deviceId, String name, Instant now) {
        return new Device(UUID.randomUUID(), userId, deviceId, sanitizeName(name), now, now, null);
    }

    public static Device restore(UUID id, UUID userId, DeviceId deviceId, String name, Instant boundAt,
                                 Instant lastSeenAt, Instant revokedAt) {
        return new Device(id, userId, deviceId, name, boundAt, lastSeenAt, revokedAt);
    }

    /** Called on each login from this device; re-binding a previously revoked device re-activates it. */
    public void seen(String deviceName, Instant now) {
        lastSeenAt = now;
        revokedAt = null;
        if (deviceName != null && !deviceName.isBlank()) {
            name = sanitizeName(deviceName);
        }
    }

    public void revoke(Instant now) {
        revokedAt = now;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "Unknown device";
        }
        String clean = name.replaceAll("[^\\p{L}\\p{N} ._()'-]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public DeviceId deviceId() {
        return deviceId;
    }

    public String name() {
        return name;
    }

    public Instant boundAt() {
        return boundAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
