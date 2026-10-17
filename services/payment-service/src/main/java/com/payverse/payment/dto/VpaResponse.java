package com.payverse.payment.dto;

import java.time.Instant;
import java.util.UUID;

public record VpaResponse(UUID id, String vpa, boolean defaultVpa, String status, Instant createdAt) {
}
