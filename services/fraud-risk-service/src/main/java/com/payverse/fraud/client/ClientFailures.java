package com.payverse.fraud.client;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;

import java.util.Map;

/** Circuit-breaker fallback: keep business errors from the remote service, map everything else to 503. */
final class ClientFailures {

    private ClientFailures() {
    }

    static <T> T rethrow(Throwable failure) {
        if (failure instanceof PayverseException payverse) {
            throw payverse;
        }
        throw new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, "A downstream service is unavailable", Map.of(), failure);
    }
}
