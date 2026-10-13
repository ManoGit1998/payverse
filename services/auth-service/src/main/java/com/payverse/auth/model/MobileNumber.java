package com.payverse.auth.model;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.common.error.PayverseException;

import java.util.regex.Pattern;

/** Indian mobile number, normalised to 10 digits (accepts {@code +91}, {@code 91} or {@code 0} prefixes). */
public record MobileNumber(String value) {

    private static final Pattern VALID = Pattern.compile("[6-9][0-9]{9}");

    public MobileNumber {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new PayverseException(AuthErrorCode.AUTH_INVALID_MOBILE, "Mobile must be a 10-digit Indian number");
        }
    }

    public static MobileNumber parse(String raw) {
        if (raw == null) {
            throw new PayverseException(AuthErrorCode.AUTH_INVALID_MOBILE, "Mobile is required");
        }
        String digits = raw.replaceAll("[\\s-]", "");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return new MobileNumber(digits);
    }

    public String masked() {
        return "******" + value.substring(6);
    }

    @Override
    public String toString() {
        return masked();
    }
}
