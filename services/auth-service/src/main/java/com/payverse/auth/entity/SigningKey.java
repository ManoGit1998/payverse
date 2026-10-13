package com.payverse.auth.entity;

import com.payverse.common.web.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** An RS256 JWT signing key. The private JWK is AES-GCM encrypted at rest. */
@Entity
@Table(name = "signing_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SigningKey {

    @Id
    private String kid;

    @Column(name = "public_jwk", nullable = false)
    private String publicJwk;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "private_key_enc", nullable = false)
    private String privateJwk;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SigningKeyStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    public static SigningKey active(String kid, String publicJwk, String privateJwk, Instant now) {
        SigningKey key = new SigningKey();
        key.kid = kid;
        key.publicJwk = publicJwk;
        key.privateJwk = privateJwk;
        key.status = SigningKeyStatus.ACTIVE;
        key.createdAt = now;
        return key;
    }

    public void retire(Instant now) {
        status = SigningKeyStatus.RETIRED;
        retiredAt = now;
    }

    public boolean isActive() {
        return status == SigningKeyStatus.ACTIVE;
    }
}
