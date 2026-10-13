package com.payverse.common.web.client;

import com.payverse.common.error.ErrorCode;

/** An error code received from another PayVerse service, re-thrown unchanged to our caller. */
public record RemoteErrorCode(String code, int httpStatus, String title) implements ErrorCode {
}
