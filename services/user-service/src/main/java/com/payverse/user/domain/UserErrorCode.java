package com.payverse.user.domain;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by user-service. */
public enum UserErrorCode implements ErrorCode {

    USER_PROFILE_NOT_FOUND(404, "User profile not found"),
    USER_INVALID_PROFILE(400, "Invalid profile details"),
    USER_KYC_INVALID_DOCUMENT(400, "Invalid identity document"),
    USER_KYC_TIER_TRANSITION(409, "KYC upgrade not allowed from the current tier"),
    USER_KYC_VERIFICATION_FAILED(422, "KYC verification failed"),
    USER_KYC_DOCUMENT_IN_USE(409, "This identity document is already linked to another account"),
    USER_KYC_DOCUMENT_TOO_LARGE(413, "Document is too large"),
    USER_KYC_UNSUPPORTED_FILE(415, "Unsupported document type"),
    USER_BANK_ACCOUNT_INVALID(400, "Invalid bank account details"),
    USER_BANK_ACCOUNT_DUPLICATE(409, "Bank account already linked"),
    USER_BANK_ACCOUNT_LIMIT(422, "Maximum number of bank accounts reached"),
    USER_BANK_ACCOUNT_NOT_FOUND(404, "Bank account not found"),
    USER_BENEFICIARY_INVALID(400, "Invalid beneficiary"),
    USER_BENEFICIARY_DUPLICATE(409, "Beneficiary already saved"),
    USER_BENEFICIARY_LIMIT(422, "Maximum number of beneficiaries reached"),
    USER_BENEFICIARY_NOT_FOUND(404, "Beneficiary not found");

    private final int httpStatus;
    private final String title;

    UserErrorCode(int httpStatus, String title) {
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
