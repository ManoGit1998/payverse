package com.payverse.wallet.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by wallet-service. */
public enum WalletErrorCode implements ErrorCode {

    WALLET_INVALID_AMOUNT(400, "Invalid amount"),
    WALLET_PIN_REQUIRED(400, "X-Txn-Pin header is required"),
    WALLET_PAYMENT_DECLINED(402, "Payment was declined"),
    WALLET_FROZEN(403, "Wallet is frozen"),
    WALLET_KYC_REQUIRED(403, "Complete KYC to use the wallet"),
    WALLET_NOT_FOUND(404, "Wallet not found"),
    WALLET_JOURNAL_NOT_FOUND(404, "Journal not found"),
    WALLET_LOCK_TIMEOUT(409, "Wallet is busy, please retry"),
    WALLET_INSUFFICIENT_BALANCE(422, "Insufficient wallet balance"),
    WALLET_LIMIT_EXCEEDED(422, "Wallet limit exceeded"),
    WALLET_INVALID_REVERSAL(422, "Journal cannot be reversed for this wallet"),
    WALLET_PAYOUT_FAILED(502, "Bank payout failed");

    private final int httpStatus;
    private final String title;

    WalletErrorCode(int httpStatus, String title) {
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
