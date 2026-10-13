package com.payverse.common.events.payload;

import com.payverse.common.events.Amount;

import java.util.UUID;

/** Events published by reconciliation-service. */
public sealed interface ReconEvents {

    record MismatchDetected(UUID runId, String mismatchType, String reference, Amount expected, Amount actual)
            implements ReconEvents {
    }
}
