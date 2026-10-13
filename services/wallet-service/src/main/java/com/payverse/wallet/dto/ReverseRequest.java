package com.payverse.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReverseRequest(@NotNull UUID journalId, @Size(max = 200) String reason) {
}
