package com.payverse.auth.service;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static com.payverse.auth.service.ServiceTestSupport.PROPERTIES;
import static com.payverse.auth.service.ServiceTestSupport.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceClientServiceTest {

    @Test
    void issuesServiceTokensForValidCredentialsOnly() {
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.issue(eq("wallet-service"), eq(Set.of("SERVICE")), isNull(), any()))
                .thenReturn(new JwtTokenProvider.IssuedToken("service-jwt", Instant.now()));
        ServiceClientService service = new ServiceClientService(jwt, PROPERTIES);

        assertThat(service.issue("wallet-service", "s3cret").value()).isEqualTo("service-jwt");
        assertError(() -> service.issue("wallet-service", "wrong"), AuthErrorCode.AUTH_INVALID_CLIENT);
        assertError(() -> service.issue("unknown", "s3cret"), AuthErrorCode.AUTH_INVALID_CLIENT);
    }
}
