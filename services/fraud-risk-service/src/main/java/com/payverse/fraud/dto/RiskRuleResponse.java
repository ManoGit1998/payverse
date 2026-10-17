package com.payverse.fraud.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RiskRuleResponse(String code, String description, int weight, boolean enabled, BigDecimal thresholdAmount,
                               Integer thresholdCount, Integer windowSeconds, BigDecimal multiplier, Instant updatedAt) {
}
