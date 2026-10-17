package com.payverse.fraud.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record AssessRequest(@NotNull UUID transactionId, @NotBlank @Size(max = 20) String type, @NotNull UUID userId, UUID payeeUserId,
                            @NotNull @DecimalMin("0.01") BigDecimal amount, @Size(min = 3, max = 3) String currency,
                            @Size(max = 100) String deviceId) {
}
