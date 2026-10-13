package com.payverse.common.security;

/** Roles carried in the JWT {@code roles} claim (mapped to {@code ROLE_*} authorities). */
public final class PayverseRoles {

    public static final String USER = "USER";
    public static final String ADMIN = "ADMIN";
    public static final String SUPPORT = "SUPPORT";
    /** Service-to-service tokens (client credentials) — required for {@code /internal/**}. */
    public static final String SERVICE = "SERVICE";

    private PayverseRoles() {
    }
}
