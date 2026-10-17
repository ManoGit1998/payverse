package com.payverse.payment.model;

import com.payverse.common.error.PayverseException;
import com.payverse.payment.exception.PaymentErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/** A UPI virtual payment address, normalised to lower case: {@code name@psp}. */
public record VpaHandle(String value) {

    public static final String PAYVERSE_PSP = "payverse";
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9.\\-_]{1,49}@[a-z]{2,30}");
    private static final Pattern LOCAL_PART = Pattern.compile("[a-z0-9][a-z0-9.]{2,29}");

    public VpaHandle {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_VPA, "UPI ID must look like name@bank");
        }
    }

    public static VpaHandle parse(String raw) {
        return new VpaHandle(raw == null ? null : raw.trim().toLowerCase(Locale.ROOT));
    }

    /** A new PayVerse handle chosen by the user: 3–30 chars of a-z, 0-9 and dots. */
    public static VpaHandle payverse(String localPart) {
        String local = localPart == null ? null : localPart.trim().toLowerCase(Locale.ROOT);
        if (local == null || !LOCAL_PART.matcher(local).matches() || local.endsWith(".") || local.contains("..")) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_VPA, "Use 3-30 letters, digits or dots");
        }
        return new VpaHandle(local + "@" + PAYVERSE_PSP);
    }

    public String psp() {
        return value.substring(value.indexOf('@') + 1);
    }

    public boolean isPayverse() {
        return PAYVERSE_PSP.equals(psp());
    }
}
