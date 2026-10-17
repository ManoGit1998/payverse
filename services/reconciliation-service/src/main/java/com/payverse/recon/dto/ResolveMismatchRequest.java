package com.payverse.recon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveMismatchRequest(@NotBlank @Size(max = 500) String note) {
}
