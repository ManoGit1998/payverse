package com.payverse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PinResetRequest(@NotBlank @Size(max = 16) String mobile) {
}
