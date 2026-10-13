package com.payverse.auth.model;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.common.error.PayverseException;

import java.util.Set;
import java.util.regex.Pattern;

/** A raw 4- or 6-digit transaction PIN. Never logged: {@link #toString()} is masked. */
public record Pin(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9]{4}|[0-9]{6}");
    private static final Set<String> BLOCKLIST = Set.of("1212", "1122", "2580", "0852", "121212", "112233", "696969");

    public Pin {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new PayverseException(AuthErrorCode.AUTH_PIN_INVALID_FORMAT, null);
        }
    }

    /** Validates a PIN chosen by the user (stricter than one presented for verification). */
    public static Pin choose(String value) {
        Pin pin = new Pin(value);
        if (pin.isWeak()) {
            throw new PayverseException(AuthErrorCode.AUTH_PIN_TOO_WEAK,
                    "Avoid repeated digits, sequences like 1234/4321 and common patterns");
        }
        return pin;
    }

    boolean isWeak() {
        return value.chars().distinct().count() == 1 || isSequence(1) || isSequence(-1) || BLOCKLIST.contains(value);
    }

    private boolean isSequence(int step) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) - value.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "Pin[****]";
    }
}
