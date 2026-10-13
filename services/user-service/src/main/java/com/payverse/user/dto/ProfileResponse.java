package com.payverse.user.dto;

import com.payverse.user.entity.KycTier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProfileResponse(UUID userId, String fullName, String email, LocalDate dateOfBirth, String mobileMasked,
                              KycTier kycTier, Instant createdAt, Instant updatedAt) {
}
