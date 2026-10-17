package com.payverse.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payverse.admin")
public record AdminProperties(String authServiceUrl, String userServiceUrl, String walletServiceUrl, String transactionServiceUrl) {

    public AdminProperties {
        authServiceUrl = authServiceUrl == null ? "http://localhost:8081" : authServiceUrl;
        userServiceUrl = userServiceUrl == null ? "http://localhost:8082" : userServiceUrl;
        walletServiceUrl = walletServiceUrl == null ? "http://localhost:8083" : walletServiceUrl;
        transactionServiceUrl = transactionServiceUrl == null ? "http://localhost:8084" : transactionServiceUrl;
    }
}
