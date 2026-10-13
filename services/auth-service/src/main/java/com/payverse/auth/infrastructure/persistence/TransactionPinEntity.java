package com.payverse.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transaction_pins")
class TransactionPinEntity {

    @Id
    @Column(name = "user_id")
    UUID userId;

    @Column(name = "pin_hash", nullable = false)
    String pinHash;

    @Column(name = "failed_attempts", nullable = false)
    int failedAttempts;

    @Column(name = "locked_until")
    Instant lockedUntil;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    long version;

    protected TransactionPinEntity() {
    }
}
