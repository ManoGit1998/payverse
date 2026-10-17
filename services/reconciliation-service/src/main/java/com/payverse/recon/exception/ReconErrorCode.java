package com.payverse.recon.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by reconciliation-service. */
public enum ReconErrorCode implements ErrorCode {

    RECON_INVALID_DATE(400, "Invalid business date"),
    RECON_RUN_NOT_FOUND(404, "Reconciliation run not found"),
    RECON_MISMATCH_NOT_FOUND(404, "Mismatch not found"),
    RECON_MISMATCH_ALREADY_RESOLVED(409, "Mismatch is already resolved");

    private final int httpStatus;
    private final String title;

    ReconErrorCode(int httpStatus, String title) {
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
