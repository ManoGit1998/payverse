package com.payverse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangePinRequest(@NotBlank String currentPin,
                               @NotBlank @Pattern(regexp = "[0-9]{4}|[0-9]{6}", message = "PIN must be 4 or 6 digits") String newPin) {

    @Override
    public String toString() {
        return "ChangePinRequest[****]";
    }
}
