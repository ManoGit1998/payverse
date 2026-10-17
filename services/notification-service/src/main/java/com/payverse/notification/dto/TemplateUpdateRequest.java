package com.payverse.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemplateUpdateRequest(@Size(max = 200) String subject, @NotBlank @Size(max = 1000) String body, Boolean active) {
}
