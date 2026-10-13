package com.payverse.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateProfileRequest(@Size(min = 2, max = 100) @Schema(example = "Asha Verma") String fullName,
                                   @Size(max = 254) @Schema(example = "asha@example.com") String email,
                                   @Past LocalDate dateOfBirth) {
}
