package com.payverse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken, @NotBlank @Size(min = 8, max = 128) String deviceId) {

    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=****, deviceId=" + deviceId + "]";
    }
}
