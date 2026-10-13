package com.payverse.auth.service;

import com.payverse.auth.config.AuthProperties;
import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import org.assertj.core.api.ThrowableAssert;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ServiceTestSupport {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);
    static final AuthProperties PROPERTIES = new AuthProperties(null, null, null, null, List.of("9000000001"), List.of("9000000002"),
            List.of(new AuthProperties.ServiceClient("wallet-service", "s3cret")), null);

    private ServiceTestSupport() {
    }

    static void assertError(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
