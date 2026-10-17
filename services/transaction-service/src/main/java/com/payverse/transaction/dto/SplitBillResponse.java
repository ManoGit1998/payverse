package com.payverse.transaction.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SplitBillResponse(UUID id, MoneyDto total, MoneyDto creatorShare, MoneyDto collected, String note,
                                List<SplitShareResponse> shares, Instant createdAt) {
}
