package com.payverse.auth.security;

import com.payverse.auth.config.AuthProperties;
import com.payverse.common.security.PayverseHttpSecurityCustomizer;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.security.PayverseSecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Public endpoints and token validation for auth-service (rest of the chain comes from common-security). */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/otp/**",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/pin/reset/**",
            "/api/v1/auth/oauth/token",
            "/.well-known/jwks.json"
    };

    /** auth-service validates its own tokens straight from its key store (no self HTTP call). */
    @Bean
    JwtDecoder jwtDecoder(SigningKeyService keys, PayverseSecurityProperties security, AuthProperties auth) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys.jwkSource()).build();
        return PayverseJwtValidators.configure(decoder, auth.issuer(), security.audience());
    }

    @Bean
    PayverseHttpSecurityCustomizer authPublicEndpoints() {
        return registry -> registry.requestMatchers(PUBLIC_PATHS).permitAll();
    }
}
