package com.payverse.transaction.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by transaction-service. */
public enum TransactionErrorCode implements ErrorCode {

    TXN_INVALID_AMOUNT(400, "Invalid amount"),
    TXN_INVALID_PAYEE(400, "Invalid payee"),
    TXN_PIN_REQUIRED(400, "X-Txn-Pin header is required"),
    TXN_INVALID_SPLIT(400, "Invalid split"),
    TXN_RISK_BLOCKED(403, "Transfer blocked by risk checks"),
    TXN_NOT_REFUNDABLE(409, "Transaction cannot be refunded"),
    TXN_NOT_FOUND(404, "Transaction not found"),
    TXN_MONEY_REQUEST_NOT_FOUND(404, "Money request not found"),
    TXN_SPLIT_NOT_FOUND(404, "Split bill not found"),
    TXN_ILLEGAL_STATE(409, "Illegal transaction state change"),
    TXN_MONEY_REQUEST_NOT_PENDING(409, "Money request is no longer pending"),
    TXN_SELF_TRANSFER(422, "You cannot send money to yourself"),
    TXN_QR_AMOUNT_MISMATCH(422, "Amount does not match the QR code");

    private final int httpStatus;
    private final String title;

    TransactionErrorCode(int httpStatus, String title) {
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
