package com.payverse.wallet.service;

import com.payverse.common.money.Money;

import java.util.UUID;

/** Charges the user for "add money" ({@link MockPaymentGatewayAdapter} until payment-service exists in Phase 4). */
public interface PaymentGateway {

    sealed interface ChargeResult {
        record Succeeded(String reference) implements ChargeResult {
        }

        record Declined(String reason) implements ChargeResult {
        }
    }

    ChargeResult charge(UUID userId, Money amount, String method, String idempotencyKey);
}
