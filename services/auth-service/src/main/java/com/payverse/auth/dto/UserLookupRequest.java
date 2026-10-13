package com.payverse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserLookupRequest(@NotBlank @Size(max = 16) String mobile) {
}
