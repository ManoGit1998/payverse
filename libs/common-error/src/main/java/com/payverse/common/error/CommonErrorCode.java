package com.payverse.common.error;

/** Error codes shared by every service. */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(400, "Request validation failed"),
    MALFORMED_REQUEST(400, "Malformed request"),
    IDEMPOTENCY_KEY_MISSING(400, "Idempotency-Key header is required"),
    UNAUTHORIZED(401, "Authentication required"),
    FORBIDDEN(403, "Access denied"),
    NOT_FOUND(404, "Resource not found"),
    METHOD_NOT_ALLOWED(405, "Method not allowed"),
    CONFLICT(409, "Conflict"),
    CONCURRENT_MODIFICATION(409, "Resource was modified concurrently, please retry"),
    REQUEST_IN_PROGRESS(409, "A request with this Idempotency-Key is still being processed"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency-Key was already used with a different request"),
    BUSINESS_RULE_VIOLATION(422, "Business rule violation"),
    RATE_LIMITED(429, "Too many requests"),
    INTERNAL_ERROR(500, "Internal server error"),
    DOWNSTREAM_UNAVAILABLE(503, "A downstream service is unavailable");

    private final int httpStatus;
    private final String title;

    CommonErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String title() {
        return title;
    }
}
