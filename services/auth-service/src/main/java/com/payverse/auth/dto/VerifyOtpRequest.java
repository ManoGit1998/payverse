package com.payverse.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
        @NotBlank @Size(max = 16) @Schema(example = "9876543210") String mobile,
        @NotBlank @Pattern(regexp = "[0-9]{4,8}") String otp,
        @NotBlank @Size(min = 8, max = 128) @Schema(example = "3f6c1d1e-device-0001") String deviceId,
        @Size(max = 100) @Schema(example = "Pixel 9") String deviceName,
        @Size(max = 32) String referralCode) {

    @Override
    public String toString() {
        return "VerifyOtpRequest[mobile=****, otp=****, deviceId=" + deviceId + "]";
    }
}
