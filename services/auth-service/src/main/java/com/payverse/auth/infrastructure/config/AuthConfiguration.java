package com.payverse.auth.infrastructure.config;

import com.payverse.auth.application.AuthProperties;
import com.payverse.auth.infrastructure.security.SigningKeyService;
import com.payverse.common.security.PayverseHttpSecurityCustomizer;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.security.PayverseSecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfiguration {

    static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/otp/**",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/pin/reset/**",
            "/api/v1/auth/oauth/token",
            "/.well-known/jwks.json"
    };

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

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
