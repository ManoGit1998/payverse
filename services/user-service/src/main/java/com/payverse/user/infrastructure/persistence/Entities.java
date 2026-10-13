package com.payverse.user.infrastructure.persistence;

import com.payverse.common.web.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "profiles")
class ProfileEntity {

    @Id
    @Column(name = "user_id")
    UUID userId;

    @Column(name = "full_name")
    String fullName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_enc")
    String email;

    @Column(name = "date_of_birth")
    LocalDate dateOfBirth;

    @Column(name = "mobile_masked")
    String mobileMasked;

    @Column(name = "kyc_tier", nullable = false)
    String kycTier;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    long version;

    protected ProfileEntity() {
    }
}

@Entity
@Table(name = "kyc_records")
class KycRecordEntity {

    @Id
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(nullable = false)
    String tier;

    @Column(name = "id_type", nullable = false)
    String idType;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "id_number_enc", nullable = false)
    String idNumber;

    @Column(name = "id_number_hash", nullable = false)
    String idNumberHash;

    @Column(name = "document_ref")
    String documentRef;

    @Column(name = "document_sha256")
    String documentSha256;

    @Column(nullable = false)
    String status;

    String reason;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected KycRecordEntity() {
    }
}

@Entity
@Table(name = "bank_accounts")
class BankAccountEntity {

    @Id
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_number_enc", nullable = false)
    String accountNumber;

    @Column(name = "account_hash", nullable = false)
    String accountHash;

    @Column(nullable = false)
    String ifsc;

    @Column(name = "holder_name", nullable = false)
    String holderName;

    @Column(name = "is_primary", nullable = false)
    boolean primary;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "removed_at")
    Instant removedAt;

    protected BankAccountEntity() {
    }
}

@Entity
@Table(name = "beneficiaries")
class BeneficiaryEntity {

    @Id
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(nullable = false)
    String type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "value_enc", nullable = false)
    String value;

    @Column(name = "value_hash", nullable = false)
    String valueHash;

    String ifsc;

    String nickname;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "removed_at")
    Instant removedAt;

    protected BeneficiaryEntity() {
    }
}
