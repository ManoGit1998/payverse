package com.payverse.auth.dto;

/** Result of an OTP login. */
public record LoginResult(AuthTokens tokens, boolean newUser, boolean pinSet) {
}
