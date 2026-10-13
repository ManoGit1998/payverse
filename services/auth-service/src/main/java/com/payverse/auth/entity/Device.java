package com.payverse.auth.entity;

import com.payverse.auth.model.DeviceId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A device bound to a user on successful OTP login. */
@Entity
@Table(name = "devices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Device {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    private String name;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public static Device bind(UUID userId, DeviceId deviceId, String name, Instant now) {
        Device device = new Device();
        device.id = UUID.randomUUID();
        device.userId = userId;
        device.deviceId = deviceId.value();
        device.name = sanitizeName(name);
        device.boundAt = now;
        device.lastSeenAt = now;
        return device;
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

    static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "Unknown device";
        }
        String clean = name.replaceAll("[^\\p{L}\\p{N} ._()'-]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }
}
