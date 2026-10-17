package com.payverse.rewards.dto;

import com.payverse.rewards.entity.RewardType;
import com.payverse.rewards.entity.RuleTrigger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashbackRuleResponse(UUID id, String code, String name, RuleTrigger trigger, RewardType rewardType, BigDecimal flatAmount,
                                   BigDecimal percent, BigDecimal minReward, BigDecimal maxReward, BigDecimal minTxnAmount,
                                   BigDecimal maxTxnAmount, boolean firstTimeOnly, Integer dailyCapPerUser, Instant activeFrom,
                                   Instant activeTo, BigDecimal budget, BigDecimal spent, int priority, boolean active, Instant updatedAt) {
}
