package com.payverse.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FreezeRequest(@NotBlank @Size(max = 200) String reason) {
}
