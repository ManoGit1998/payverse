package com.payverse.wallet.service;

import com.payverse.common.money.Money;

import java.util.UUID;

/** Charges the user for "add money" (implemented by {@code PaymentServiceGateway} → payment-service). */
public interface PaymentGateway {

    sealed interface ChargeResult {
        record Succeeded(String reference) implements ChargeResult {
        }

        record Declined(String reason) implements ChargeResult {
        }
    }

    ChargeResult charge(UUID userId, Money amount, String method, String idempotencyKey);
}
