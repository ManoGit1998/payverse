package com.payverse.rewards.dto;

import java.util.List;

public record RewardsSummaryResponse(MoneyDto totalCredited, MoneyDto pendingCredit, List<RewardResponse> recent) {
}
