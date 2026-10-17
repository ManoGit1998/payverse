package com.payverse.admin.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by admin-service. */
public enum AdminErrorCode implements ErrorCode {

    ADMIN_INVALID_SEARCH(400, "Search by mobile, name or KYC tier"),
    ADMIN_INVALID_DISPUTE(400, "Invalid dispute"),
    ADMIN_MAKER_CHECKER(403, "The requester cannot approve or reject their own refund"),
    ADMIN_USER_NOT_FOUND(404, "User not found"),
    ADMIN_DISPUTE_NOT_FOUND(404, "Dispute not found"),
    ADMIN_REFUND_NOT_FOUND(404, "Refund request not found"),
    ADMIN_DISPUTE_EXISTS(409, "There is already an open dispute for this transaction"),
    ADMIN_DISPUTE_RESOLVED(409, "Dispute is already resolved"),
    ADMIN_REFUND_EXISTS(409, "A refund for this transaction is already in progress"),
    ADMIN_REFUND_DECIDED(409, "Refund request was already decided"),
    ADMIN_NOT_REFUNDABLE(422, "Only completed (SUCCESS) transfers can be disputed or refunded");

    private final int httpStatus;
    private final String title;

    AdminErrorCode(int httpStatus, String title) {
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
