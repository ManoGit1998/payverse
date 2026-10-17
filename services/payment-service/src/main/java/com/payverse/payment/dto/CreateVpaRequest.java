package com.payverse.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVpaRequest(@NotBlank @Size(min = 3, max = 30) @Schema(example = "asha.verma", description = "becomes asha.verma@payverse") String name) {
}
