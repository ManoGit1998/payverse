package com.payverse.transaction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RefundRequest(@NotNull UUID refundId, @Size(max = 200) String reason) {
}
