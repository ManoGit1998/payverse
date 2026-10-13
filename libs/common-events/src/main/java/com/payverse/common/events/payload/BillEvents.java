package com.payverse.common.events.payload;

import com.payverse.common.events.Amount;

import java.util.UUID;

/** Events published by bill-payment-service. */
public sealed interface BillEvents {

    record BillPaymentCompleted(UUID billPaymentId, UUID userId, String category, String billerName, Amount amount,
                                String billerReference) implements BillEvents {
    }

    record BillPaymentFailed(UUID billPaymentId, UUID userId, String category, Amount amount, String reasonCode)
            implements BillEvents {
    }

    record MandateExecuted(UUID mandateId, UUID userId, UUID billPaymentId, String status) implements BillEvents {
    }
}
