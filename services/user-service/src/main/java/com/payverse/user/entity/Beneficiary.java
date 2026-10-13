package com.payverse.user.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.EncryptedStringConverter;
import com.payverse.user.exception.UserErrorCode;
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
import java.util.Locale;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** A saved payee: a mobile number, a UPI ID (VPA) or a bank account (+ IFSC). Value encrypted at rest. */
@Entity
@Table(name = "beneficiaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Beneficiary {

    public static final int MAX_PER_USER = 50;
    private static final Pattern MOBILE = Pattern.compile("[6-9][0-9]{9}");
    private static final Pattern VPA = Pattern.compile("[a-z0-9.\\-_]{2,256}@[a-z]{2,64}");
    private static final Pattern ACCOUNT = Pattern.compile("[0-9]{9,18}");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final Pattern NICKNAME = Pattern.compile("[\\p{L}\\p{N} .'&()-]{1,50}");

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BeneficiaryType type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "value_enc", nullable = false)
    private String value;

    @Column(name = "value_hash", nullable = false)
    private String valueHash;

    private String ifsc;

    private String nickname;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    /** Normalises (mobile prefixes, VPA case, spaces) and validates per type. */
    public static Beneficiary save(UUID userId, BeneficiaryType type, String rawValue, String rawIfsc, String rawNickname,
                                   UnaryOperator<String> indexer, Instant now) {
        String value = normalise(type, rawValue);
        String ifsc = rawIfsc == null || rawIfsc.isBlank() ? null : rawIfsc.trim().toUpperCase(Locale.ROOT);
        String nickname = rawNickname == null || rawNickname.isBlank() ? null : rawNickname.trim();
        boolean valid = value != null && switch (type) {
            case MOBILE -> MOBILE.matcher(value).matches() && ifsc == null;
            case VPA -> VPA.matcher(value).matches() && ifsc == null;
            case BANK_ACCOUNT -> ACCOUNT.matcher(value).matches() && ifsc != null && IFSC.matcher(ifsc).matches();
        };
        if (!valid) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_INVALID, "Invalid " + type + " beneficiary");
        }
        if (nickname != null && !NICKNAME.matcher(nickname).matches()) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_INVALID, "Nickname must be 1-50 characters");
        }
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.id = UUID.randomUUID();
        beneficiary.userId = userId;
        beneficiary.type = type;
        beneficiary.value = value;
        beneficiary.valueHash = indexer.apply(type + ":" + value);
        beneficiary.ifsc = ifsc;
        beneficiary.nickname = nickname;
        beneficiary.createdAt = now;
        return beneficiary;
    }

    public static String normalise(BeneficiaryType type, String raw) {
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case MOBILE -> raw.replaceAll("[\\s-]", "").replaceFirst("^(\\+91|91(?=[0-9]{10}$)|0(?=[0-9]{10}$))", "");
            case VPA -> raw.trim().toLowerCase(Locale.ROOT);
            case BANK_ACCOUNT -> raw.replaceAll("\\s", "");
        };
    }

    public void remove(Instant now) {
        removedAt = now;
    }

    public String getMaskedValue() {
        return switch (type) {
            case MOBILE, BANK_ACCOUNT -> "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
            case VPA -> value;
        };
    }

    @Override
    public String toString() {
        return "Beneficiary[id=" + id + ", " + type + " " + getMaskedValue() + "]";
    }
}
