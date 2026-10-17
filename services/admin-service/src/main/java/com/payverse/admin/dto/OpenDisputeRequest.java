package com.payverse.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OpenDisputeRequest(@NotNull UUID transactionId, @NotBlank @Size(max = 500) String reason) {
}
