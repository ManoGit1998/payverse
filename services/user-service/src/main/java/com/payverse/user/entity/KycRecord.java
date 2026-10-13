package com.payverse.user.entity;

import com.payverse.common.web.crypto.EncryptedStringConverter;
import com.payverse.user.model.IdDocument;
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
import java.util.UUID;

/** One KYC submission and its outcome (append-only history). The document number is encrypted. */
@Entity
@Table(name = "kyc_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KycRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycTier tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", nullable = false)
    private IdDocumentType idType;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "id_number_enc", nullable = false)
    private String idNumber;

    /** HMAC blind index of type + number (one verified document per account). */
    @Column(name = "id_number_hash", nullable = false)
    private String idNumberHash;

    @Column(name = "document_ref")
    private String documentRef;

    @Column(name = "document_sha256")
    private String documentSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycStatus status;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static KycRecord verified(UUID userId, KycTier tier, IdDocument document, String documentHash,
                                     String documentRef, String documentSha256, Instant now) {
        KycRecord record = create(userId, tier, document, documentHash, KycStatus.VERIFIED, now);
        record.documentRef = documentRef;
        record.documentSha256 = documentSha256;
        return record;
    }

    public static KycRecord rejected(UUID userId, KycTier tier, IdDocument document, String documentHash, String reason, Instant now) {
        KycRecord record = create(userId, tier, document, documentHash, KycStatus.REJECTED, now);
        record.reason = reason;
        return record;
    }

    public String getMaskedNumber() {
        return IdDocument.mask(idNumber);
    }

    private static KycRecord create(UUID userId, KycTier tier, IdDocument document, String hash, KycStatus status, Instant now) {
        KycRecord record = new KycRecord();
        record.id = UUID.randomUUID();
        record.userId = userId;
        record.tier = tier;
        record.idType = document.type();
        record.idNumber = document.number();
        record.idNumberHash = hash;
        record.status = status;
        record.createdAt = now;
        return record;
    }
}
