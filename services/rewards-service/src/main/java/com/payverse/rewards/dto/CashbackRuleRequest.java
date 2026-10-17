package com.payverse.rewards.dto;

import com.payverse.rewards.entity.RewardType;
import com.payverse.rewards.entity.RuleTrigger;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Create (with {@code code}) or replace a rule. Amounts are rupees; percent is 0–100. */
public record CashbackRuleRequest(@Size(max = 40) String code,
                                  @NotBlank @Size(max = 120) String name,
                                  @NotNull RuleTrigger trigger,
                                  @NotNull RewardType rewardType,
                                  @DecimalMin("0.01") @Digits(integer = 5, fraction = 2) BigDecimal flatAmount,
                                  @DecimalMin("0.01") @Digits(integer = 3, fraction = 2) BigDecimal percent,
                                  @DecimalMin("0.01") @Digits(integer = 5, fraction = 2) BigDecimal minReward,
                                  @DecimalMin("0.01") @Digits(integer = 5, fraction = 2) BigDecimal maxReward,
                                  @DecimalMin("0.00") @Digits(integer = 7, fraction = 2) BigDecimal minTxnAmount,
                                  @DecimalMin("0.00") @Digits(integer = 7, fraction = 2) BigDecimal maxTxnAmount,
                                  Boolean firstTimeOnly,
                                  @Min(1) Integer dailyCapPerUser,
                                  Instant activeFrom,
                                  Instant activeTo,
                                  @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal budget,
                                  Integer priority,
                                  Boolean active) {
}
