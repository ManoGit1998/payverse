package com.payverse.common.events.payload;

import java.util.List;
import java.util.UUID;

/** Events published by fraud-risk-service. */
public sealed interface FraudEvents {

    record TransactionFlagged(UUID assessmentId, UUID transactionId, UUID userId, int score, String decision,
                              List<String> reasons) implements FraudEvents {
    }
}
