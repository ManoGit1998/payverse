package com.payverse.user.entity;

import java.util.regex.Pattern;

/** Officially valid documents and their number formats. */
public enum IdDocumentType {

    PAN("[A-Z]{5}[0-9]{4}[A-Z]", KycTier.MIN_KYC),
    VOTER_ID("[A-Z]{3}[0-9]{7}", KycTier.MIN_KYC),
    DRIVING_LICENSE("[A-Z]{2}[0-9]{13}", KycTier.MIN_KYC),
    PASSPORT("[A-Z][0-9]{7}", KycTier.MIN_KYC),
    AADHAAR("[2-9][0-9]{11}", KycTier.FULL_KYC);

    private final Pattern format;
    private final KycTier tier;

    IdDocumentType(String regex, KycTier tier) {
        this.format = Pattern.compile(regex);
        this.tier = tier;
    }

    public boolean matches(String normalisedNumber) {
        return format.matcher(normalisedNumber).matches();
    }

    /** The tier this document type is accepted for. */
    public KycTier tier() {
        return tier;
    }
}
