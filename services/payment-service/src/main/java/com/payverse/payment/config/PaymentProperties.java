package com.payverse.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param transactionServiceUrl base URL of transaction-service (collect approvals start transfers there)
 * @param userServiceUrl        base URL of user-service (payee names)
 * @param maxDynamicQrValidity  longest allowed validity of a dynamic QR (default 60m)
 */
@ConfigurationProperties("payverse.payment")
public record PaymentProperties(String transactionServiceUrl, String userServiceUrl, Duration maxDynamicQrValidity) {

    public PaymentProperties {
        transactionServiceUrl = transactionServiceUrl == null ? "http://localhost:8084" : transactionServiceUrl;
        userServiceUrl = userServiceUrl == null ? "http://localhost:8082" : userServiceUrl;
        maxDynamicQrValidity = maxDynamicQrValidity == null ? Duration.ofMinutes(60) : maxDynamicQrValidity;
    }
}
