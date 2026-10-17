package com.payverse.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DecodeQrRequest(@NotBlank @Size(max = 500) @Schema(example = "upi://pay?pa=ravi@payverse&am=100.00&cu=INR") String payload) {
}
