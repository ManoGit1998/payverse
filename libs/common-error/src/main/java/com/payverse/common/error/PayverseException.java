package com.payverse.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base exception carrying an {@link ErrorCode}. Rendered as an RFC 9457 Problem Detail by the
 * web layer. Pure Java: domain code may throw it (or a subclass) without depending on Spring.
 */
public class PayverseException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> properties;

    public PayverseException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of(), null);
    }

    public PayverseException(ErrorCode errorCode, String detail, Map<String, Object> properties) {
        this(errorCode, detail, properties, null);
    }

    public PayverseException(ErrorCode errorCode, String detail, Map<String, Object> properties, Throwable cause) {
        super(detail != null ? detail : Objects.requireNonNull(errorCode, "errorCode").title(), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Extra, non-sensitive properties added to the problem response (e.g. {@code walletId}). */
    public Map<String, Object> properties() {
        return properties;
    }

    public static PayverseException notFound(String resource, Object id) {
        return new PayverseException(CommonErrorCode.NOT_FOUND, resource + " not found",
                Map.of("resource", resource, "id", String.valueOf(id)));
    }

    public static PayverseException businessRule(String detail) {
        return new PayverseException(CommonErrorCode.BUSINESS_RULE_VIOLATION, detail);
    }
}
