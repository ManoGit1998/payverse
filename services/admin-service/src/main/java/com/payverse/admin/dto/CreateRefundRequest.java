package com.payverse.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Full refund of a SUCCESS transfer (the amount is the transfer's). */
public record CreateRefundRequest(@NotNull UUID transactionId, @NotBlank @Size(max = 200) String reason) {
}
