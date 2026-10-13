package com.payverse.common.error;

/**
 * A stable, machine-readable error code. Each service defines its own enum implementing this
 * interface (e.g. {@code WalletErrorCode.INSUFFICIENT_BALANCE}). Pure Java: safe for domain layers.
 */
public interface ErrorCode {

    /** Stable code exposed to clients, e.g. {@code WALLET_INSUFFICIENT_BALANCE}. */
    String code();

    /** HTTP status the code maps to at the API boundary. */
    int httpStatus();

    /** Short, human-readable title (RFC 9457 {@code title}). */
    String title();
}
