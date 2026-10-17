package com.payverse.fraud.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Replaces the rule's settings (omitted thresholds fall back to the rule's built-in defaults). */
public record RiskRuleUpdateRequest(@NotNull @Min(0) @Max(100) Integer weight, @NotNull Boolean enabled, BigDecimal thresholdAmount,
                                    Integer thresholdCount, Integer windowSeconds, BigDecimal multiplier) {
}
