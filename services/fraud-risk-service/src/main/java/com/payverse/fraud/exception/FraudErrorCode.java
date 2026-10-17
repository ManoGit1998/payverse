package com.payverse.fraud.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by fraud-risk-service. */
public enum FraudErrorCode implements ErrorCode {

    FRAUD_INVALID_RULE(400, "Invalid risk rule settings"),
    FRAUD_INVALID_RESOLUTION(400, "Invalid flag resolution"),
    FRAUD_FLAG_NOT_FOUND(404, "Flag not found"),
    FRAUD_RULE_NOT_FOUND(404, "Risk rule not found"),
    FRAUD_FLAG_ALREADY_RESOLVED(409, "Flag is already resolved");

    private final int httpStatus;
    private final String title;

    FraudErrorCode(int httpStatus, String title) {
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
