package com.payverse.rewards.dto;

import jakarta.validation.constraints.NotNull;

public record CouponStatusRequest(@NotNull Boolean active) {
}
