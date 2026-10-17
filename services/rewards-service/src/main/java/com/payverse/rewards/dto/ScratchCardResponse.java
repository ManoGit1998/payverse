package com.payverse.rewards.dto;

import com.payverse.rewards.entity.ScratchCardStatus;

import java.time.Instant;
import java.util.UUID;

/** {@code amount} is null until the card is scratched. */
public record ScratchCardResponse(UUID id, ScratchCardStatus status, MoneyDto amount, String ruleCode, String sourceType, Instant expiresAt,
                                  Instant scratchedAt, UUID rewardId, Instant createdAt) {
}
