package com.payverse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SetPinRequest(@NotBlank @Pattern(regexp = "[0-9]{4}|[0-9]{6}", message = "PIN must be 4 or 6 digits") String pin) {

    @Override
    public String toString() {
        return "SetPinRequest[****]";
    }
}
