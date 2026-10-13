package com.payverse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PinResetConfirmRequest(@NotBlank @Size(max = 16) String mobile,
                                     @NotBlank @Pattern(regexp = "[0-9]{4,8}") String otp,
                                     @NotBlank @Pattern(regexp = "[0-9]{4}|[0-9]{6}", message = "PIN must be 4 or 6 digits") String newPin) {

    @Override
    public String toString() {
        return "PinResetConfirmRequest[****]";
    }
}
