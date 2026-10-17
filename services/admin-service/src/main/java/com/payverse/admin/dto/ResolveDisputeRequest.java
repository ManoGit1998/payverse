package com.payverse.admin.dto;

import com.payverse.admin.entity.DisputeOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveDisputeRequest(@NotNull DisputeOutcome outcome, @Size(max = 500) String note) {
}
