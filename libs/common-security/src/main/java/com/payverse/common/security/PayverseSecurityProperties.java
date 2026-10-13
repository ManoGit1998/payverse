package com.payverse.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param jwkSetUri   auth-service JWKS endpoint, e.g. {@code http://localhost:8081/.well-known/jwks.json}
 * @param issuer      expected {@code iss}
 * @param audience    expected {@code aud} (default {@code payverse})
 * @param publicPaths extra unauthenticated Ant-style paths for this service
 */
@ConfigurationProperties("payverse.security")
public record PayverseSecurityProperties(String jwkSetUri, String issuer, String audience, List<String> publicPaths) {

    public PayverseSecurityProperties {
        jwkSetUri = jwkSetUri == null ? "http://localhost:8081/.well-known/jwks.json" : jwkSetUri;
        issuer = issuer == null ? "http://localhost:8081" : issuer;
        audience = audience == null ? PayverseClaims.DEFAULT_AUDIENCE : audience;
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }
}
