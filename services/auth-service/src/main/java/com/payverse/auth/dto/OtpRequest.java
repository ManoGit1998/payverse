package com.payverse.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OtpRequest(@NotBlank @Size(max = 16) @Schema(example = "9876543210") String mobile) {
}
