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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** A user's profile and KYC tier. Name and date of birth lock once KYC is done. */
@Entity
@Table(name = "profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {

    public static final int MINIMUM_KYC_AGE = 18;
    private static final Pattern NAME = Pattern.compile("[\\p{L} .'-]{2,100}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,180}\\.[A-Za-z]{2,24}");

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "full_name")
    private String fullName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_enc")
    private String email;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "mobile_masked")
    private String mobileMasked;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_tier", nullable = false)
    private KycTier kycTier;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static UserProfile create(UUID userId, String mobileMasked, Instant now) {
        UserProfile profile = new UserProfile();
        profile.userId = userId;
        profile.mobileMasked = mobileMasked;
        profile.kycTier = KycTier.NONE;
        profile.createdAt = now;
        profile.updatedAt = now;
        return profile;
    }

    public void attachMobile(String masked, Instant now) {
        if (mobileMasked == null && masked != null) {
            mobileMasked = masked;
            updatedAt = now;
        }
    }

    public void updateDetails(String newName, String newEmail, LocalDate newDob, Instant now) {
        String name = newName == null ? fullName : validName(newName);
        String mail = newEmail == null ? email : validEmail(newEmail);
        LocalDate dob = newDob == null ? dateOfBirth : validDob(newDob, now);
        boolean identityChanged = (newName != null && !name.equals(fullName)) || (newDob != null && !dob.equals(dateOfBirth));
        if (kycTier != KycTier.NONE && identityChanged) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Name and date of birth are locked after KYC");
        }
        fullName = name;
        email = mail;
        dateOfBirth = dob;
        updatedAt = now;
    }

    /** Validates that this profile may move to {@code target} with the given KYC identity (no change yet). */
    public void checkKycUpgrade(KycTier target, String kycName, LocalDate kycDob, Instant now) {
        if (!kycTier.canUpgradeTo(target)) {
            throw new PayverseException(UserErrorCode.USER_KYC_TIER_TRANSITION, "Cannot move from " + kycTier + " to " + target);
        }
        String name = kycName == null ? fullName : validName(kycName);
        LocalDate dob = kycDob == null ? dateOfBirth : validDob(kycDob, now);
        if (name == null || dob == null) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Full name and date of birth are required for KYC");
        }
        if (Period.between(dob, LocalDate.ofInstant(now, ZoneOffset.UTC)).getYears() < MINIMUM_KYC_AGE) {
            throw new PayverseException(UserErrorCode.USER_KYC_VERIFICATION_FAILED, "KYC requires age " + MINIMUM_KYC_AGE + "+");
        }
    }

    /** Applies a verified upgrade; the KYC name/DOB become the profile's legal identity. */
    public void upgradeKyc(KycTier target, String kycName, LocalDate kycDob, Instant now) {
        checkKycUpgrade(target, kycName, kycDob, now);
        if (kycName != null) {
            fullName = validName(kycName);
        }
        if (kycDob != null) {
            dateOfBirth = kycDob;
        }
        kycTier = target;
        updatedAt = now;
    }

    private static String validName(String name) {
        String trimmed = name.trim().replaceAll("\\s+", " ");
        if (!NAME.matcher(trimmed).matches()) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Name must be 2-100 letters");
        }
        return trimmed;
    }

    private static String validEmail(String email) {
        String trimmed = email.trim();
        if (!EMAIL.matcher(trimmed).matches()) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Invalid e-mail address");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static LocalDate validDob(LocalDate dob, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (dob.isAfter(today) || dob.isBefore(today.minusYears(120))) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Invalid date of birth");
        }
        return dob;
    }
}
