package com.payverse.rewards.dto;

import java.time.Instant;
import java.util.UUID;

public record CouponResponse(UUID id, String code, String description, MoneyDto cashback, Instant validFrom, Instant validUntil,
                             int usageLimit, int usedCount, boolean active) {
}
