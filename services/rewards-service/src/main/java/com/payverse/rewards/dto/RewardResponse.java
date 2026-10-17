package com.payverse.rewards.dto;

import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.RewardStatus;

import java.time.Instant;
import java.util.UUID;

public record RewardResponse(UUID id, String ruleCode, RewardSource sourceType, String sourceId, MoneyDto amount, RewardStatus status,
                             Instant createdAt, Instant creditedAt) {
}
