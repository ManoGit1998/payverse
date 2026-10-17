package com.payverse.bill.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by bill-payment-service. */
public enum BillErrorCode implements ErrorCode {

    BILL_INVALID_ACCOUNT(400, "Invalid account for this biller"),
    BILL_FETCH_NOT_SUPPORTED(400, "This biller has no bills to fetch"),
    BILL_PIN_REQUIRED(400, "Transaction PIN is required"),
    BILL_INVALID_MANDATE(400, "Invalid auto-pay mandate"),
    BILL_BILLER_NOT_FOUND(404, "Biller not found"),
    BILL_ACCOUNT_NOT_FOUND(404, "The biller does not know this account"),
    BILL_NOT_FOUND(404, "Bill payment not found"),
    BILL_MANDATE_NOT_FOUND(404, "Mandate not found"),
    BILL_AMOUNT_OUT_OF_RANGE(422, "Amount is outside this biller's limits"),
    BILL_BILLER_DECLINED(422, "The biller declined the payment"),
    BILL_BILLER_UNAVAILABLE(503, "The biller is unavailable");

    private final int httpStatus;
    private final String title;

    BillErrorCode(int httpStatus, String title) {
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
