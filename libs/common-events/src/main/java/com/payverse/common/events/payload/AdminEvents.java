package com.payverse.common.events.payload;

import com.payverse.common.events.Amount;

import java.util.UUID;

/** Events published by admin-service. */
public sealed interface AdminEvents {

    record RefundApproved(UUID refundId, UUID transactionId, Amount amount, String requestedBy, String approvedBy,
                          String reason) implements AdminEvents {
    }
}
