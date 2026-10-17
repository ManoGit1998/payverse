package com.payverse.payment.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by payment-service. */
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_INVALID_VPA(400, "Invalid UPI ID"),
    PAYMENT_INVALID_QR(400, "Invalid or unsupported QR code"),
    PAYMENT_INVALID_AMOUNT(400, "Invalid amount"),
    PAYMENT_PIN_REQUIRED(400, "X-Txn-Pin header is required"),
    PAYMENT_NOT_YOURS(403, "This resource belongs to another user"),
    PAYMENT_VPA_NOT_FOUND(404, "UPI ID not found"),
    PAYMENT_QR_NOT_FOUND(404, "QR code not found"),
    PAYMENT_COLLECT_NOT_FOUND(404, "Collect request not found"),
    PAYMENT_VPA_TAKEN(409, "UPI ID is already taken"),
    PAYMENT_COLLECT_NOT_PENDING(409, "Collect request is no longer pending"),
    PAYMENT_QR_EXPIRED(410, "QR code has expired"),
    PAYMENT_VPA_LIMIT(422, "Maximum number of UPI IDs reached"),
    PAYMENT_SELF_COLLECT(422, "You cannot request money from yourself"),
    PAYMENT_VPA_EXTERNAL_UNSUPPORTED(422, "UPI IDs of other apps are not supported in the simulation");

    private final int httpStatus;
    private final String title;

    PaymentErrorCode(int httpStatus, String title) {
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
