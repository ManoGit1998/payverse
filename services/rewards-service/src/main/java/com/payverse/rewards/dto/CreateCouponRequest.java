package com.payverse.rewards.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateCouponRequest(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 200) String description,
                                  @NotNull @DecimalMin("0.01") @Digits(integer = 5, fraction = 2) BigDecimal cashback,
                                  Instant validFrom, @NotNull Instant validUntil, @Min(1) int usageLimit) {
}
