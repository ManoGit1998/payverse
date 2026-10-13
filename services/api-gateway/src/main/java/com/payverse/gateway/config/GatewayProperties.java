package com.payverse.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param corsAllowedOrigins browser origins allowed to call the API
 */
@ConfigurationProperties("payverse.gateway")
public record GatewayProperties(List<String> corsAllowedOrigins) {

    public GatewayProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of("http://localhost:3000") : List.copyOf(corsAllowedOrigins);
    }
}
