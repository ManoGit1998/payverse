package com.payverse.auth.infrastructure.persistence;

import com.payverse.common.web.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserEntity {

    @Id
    UUID id;

    /** AES-GCM encrypted mobile number. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mobile_enc", nullable = false)
    String mobile;

    /** HMAC blind index of the mobile number, for lookups. */
    @Column(name = "mobile_hash", nullable = false, unique = true)
    String mobileHash;

    @Column(nullable = false)
    String status;

    /** Comma-separated role names. */
    @Column(nullable = false)
    String roles;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Version
    long version;

    protected UserEntity() {
    }
}
