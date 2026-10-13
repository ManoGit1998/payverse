package com.payverse.user.model;

import com.payverse.common.error.PayverseException;
import com.payverse.user.entity.IdDocumentType;
import com.payverse.user.exception.UserErrorCode;

import java.util.Locale;
import java.util.Objects;

/** A validated identity document number (normalised: upper case, no spaces/dashes). Masked in toString. */
public record IdDocument(IdDocumentType type, String number) {

    public IdDocument {
        Objects.requireNonNull(type, "type");
        if (number == null || !type.matches(number)) {
            throw new PayverseException(UserErrorCode.USER_KYC_INVALID_DOCUMENT, "Invalid " + type + " number");
        }
        if (type == IdDocumentType.AADHAAR && !Verhoeff.isValid(number)) {
            throw new PayverseException(UserErrorCode.USER_KYC_INVALID_DOCUMENT, "Invalid Aadhaar checksum");
        }
    }

    public static IdDocument of(IdDocumentType type, String rawNumber) {
        String normalised = rawNumber == null ? null : rawNumber.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return new IdDocument(type, normalised);
    }

    public String masked() {
        return mask(number);
    }

    public static String mask(String number) {
        return "*".repeat(number.length() - 4) + number.substring(number.length() - 4);
    }

    @Override
    public String toString() {
        return "IdDocument[" + type + " " + masked() + "]";
    }
}
