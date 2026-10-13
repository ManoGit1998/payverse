package com.payverse.common.web.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials this service uses to obtain SERVICE tokens from auth-service (client_credentials).
 *
 * @param clientId     e.g. {@code wallet-service}
 * @param clientSecret from {@code SERVICE_CLIENT_SECRET}
 * @param tokenUri     e.g. {@code http://localhost:8081/api/v1/auth/oauth/token}
 */
@ConfigurationProperties("payverse.service-client")
public record ServiceClientProperties(String clientId, String clientSecret, String tokenUri) {

    @Override
    public String toString() {
        return "ServiceClientProperties[clientId=" + clientId + ", tokenUri=" + tokenUri + ", clientSecret=****]";
    }
}
