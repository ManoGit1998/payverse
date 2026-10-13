package com.payverse.common.security;

/** JWT claim names and values used by auth-service tokens. */
public final class PayverseClaims {

    public static final String ROLES = "roles";
    public static final String DEVICE_ID = "device_id";
    public static final String TOKEN_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";
    public static final String DEFAULT_AUDIENCE = "payverse";

    private PayverseClaims() {
    }
}
