package com.payverse.common.events.payload;

import com.payverse.common.events.Amount;

import java.time.Instant;
import java.util.UUID;

/** Events published by payment-service (simulated UPI / payment gateway). */
public sealed interface PaymentEvents {

    record CollectRequested(UUID collectId, String payerVpa, String payeeVpa, UUID payerUserId, Amount amount,
                            Instant expiresAt) implements PaymentEvents {
    }

    record PgChargeSettled(UUID orderId, UUID userId, Amount amount, String provider, String providerReference,
                           String status) implements PaymentEvents {
    }
}
