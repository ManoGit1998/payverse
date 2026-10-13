package com.payverse.user.domain;

import com.payverse.common.error.PayverseException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable profile aggregate; state changes return a new instance. */
public record UserProfile(UUID userId, String fullName, String email, LocalDate dateOfBirth, String mobileMasked,
                          KycTier kycTier, Instant createdAt, Instant updatedAt, long version) {

    private static final Pattern NAME = Pattern.compile("[\\p{L} .'-]{2,100}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,180}\\.[A-Za-z]{2,24}");
    public static final int MINIMUM_KYC_AGE = 18;

    public UserProfile {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(kycTier);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }

    public static UserProfile create(UUID userId, String mobileMasked, Instant now) {
        return new UserProfile(userId, null, null, null, mobileMasked, KycTier.NONE, now, now, 0);
    }

    public UserProfile withMobileMasked(String masked, Instant now) {
        return new UserProfile(userId, fullName, email, dateOfBirth, masked, kycTier, createdAt, now, version);
    }

    public UserProfile updateDetails(String newName, String newEmail, LocalDate newDob, Instant now) {
        String name = newName == null ? fullName : validName(newName);
        String mail = newEmail == null ? email : validEmail(newEmail);
        LocalDate dob = newDob == null ? dateOfBirth : validDob(newDob, now);
        if (kycTier != KycTier.NONE && ((newName != null && !name.equals(fullName)) || (newDob != null && !dob.equals(dateOfBirth)))) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Name and date of birth are locked after KYC");
        }
        return new UserProfile(userId, name, mail, dob, mobileMasked, kycTier, createdAt, now, version);
    }

    /** Applies a verified KYC upgrade, recording the KYC name/DOB as the profile's legal identity. */
    public UserProfile upgradeKyc(KycTier target, String kycName, LocalDate kycDob, Instant now) {
        if (!kycTier.canUpgradeTo(target)) {
            throw new PayverseException(UserErrorCode.USER_KYC_TIER_TRANSITION,
                    "Cannot move from " + kycTier + " to " + target);
        }
        String name = kycName == null ? fullName : validName(kycName);
        LocalDate dob = kycDob == null ? dateOfBirth : validDob(kycDob, now);
        if (name == null || dob == null) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Full name and date of birth are required for KYC");
        }
        if (Period.between(dob, LocalDate.ofInstant(now, java.time.ZoneOffset.UTC)).getYears() < MINIMUM_KYC_AGE) {
            throw new PayverseException(UserErrorCode.USER_KYC_VERIFICATION_FAILED, "KYC requires age " + MINIMUM_KYC_AGE + "+");
        }
        return new UserProfile(userId, name, email, dob, mobileMasked, target, createdAt, now, version);
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
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private static LocalDate validDob(LocalDate dob, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        if (dob.isAfter(today) || dob.isBefore(today.minusYears(120))) {
            throw new PayverseException(UserErrorCode.USER_INVALID_PROFILE, "Invalid date of birth");
        }
        return dob;
    }
}
