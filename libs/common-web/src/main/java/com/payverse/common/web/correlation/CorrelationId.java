package com.payverse.common.web.correlation;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation-ID conventions shared by HTTP filters, REST clients and Kafka headers. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    /** Returns the incoming id when it is well-formed, otherwise a new random id. */
    public static String sanitizeOrGenerate(String incoming) {
        return incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
