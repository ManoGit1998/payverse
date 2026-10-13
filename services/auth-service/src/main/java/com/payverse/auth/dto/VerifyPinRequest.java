package com.payverse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record VerifyPinRequest(@NotNull UUID userId, @NotBlank @Size(max = 6) String pin) {

    @Override
    public String toString() {
        return "VerifyPinRequest[userId=" + userId + ", pin=****]";
    }
}
