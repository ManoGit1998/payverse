package com.payverse.common.events.payload;

import com.payverse.common.events.Amount;

import java.time.Instant;
import java.util.UUID;

/** Events published by rewards-service. */
public sealed interface RewardsEvents {

    record CashbackGranted(UUID rewardId, UUID userId, Amount amount, UUID sourceTransactionId, String ruleCode)
            implements RewardsEvents {
    }

    record ScratchCardIssued(UUID cardId, UUID userId, String sourceType, String sourceId, String ruleCode, Instant expiresAt)
            implements RewardsEvents {
    }
}
