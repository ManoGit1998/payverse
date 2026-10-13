package com.payverse.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
class DeviceEntity {

    @Id
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "device_id", nullable = false)
    String deviceId;

    String name;

    @Column(name = "bound_at", nullable = false)
    Instant boundAt;

    @Column(name = "last_seen_at")
    Instant lastSeenAt;

    @Column(name = "revoked_at")
    Instant revokedAt;

    protected DeviceEntity() {
    }
}
