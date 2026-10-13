package com.payverse.common.error.web;

import com.payverse.common.error.ErrorCode;

import java.net.URI;
import java.util.Locale;

/** Builds the RFC 9457 {@code type} URI for an error code. */
public final class ProblemTypes {

    public static final String BASE = "https://payverse.dev/problems/";

    private ProblemTypes() {
    }

    public static URI of(ErrorCode errorCode) {
        return URI.create(BASE + errorCode.code().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
