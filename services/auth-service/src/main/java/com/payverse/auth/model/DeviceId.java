package com.payverse.auth.model;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.common.error.PayverseException;

import java.util.regex.Pattern;

/** Client-generated, stable device identifier (e.g. a UUID created on first app launch). */
public record DeviceId(String value) {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    public DeviceId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new PayverseException(AuthErrorCode.AUTH_INVALID_DEVICE, "deviceId must be 8-128 characters of [A-Za-z0-9._:-]");
        }
    }
}
