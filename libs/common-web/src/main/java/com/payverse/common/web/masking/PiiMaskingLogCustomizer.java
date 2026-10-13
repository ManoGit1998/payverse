package com.payverse.common.web.masking;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Scrubs PII from every string written by Spring Boot structured (JSON) logging.
 * Enabled with {@code logging.structured.json.customizer=com.payverse.common.web.masking.PiiMaskingLogCustomizer}.
 */
public class PiiMaskingLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, PiiMasker::scrub));
    }
}
