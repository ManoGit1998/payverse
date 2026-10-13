package com.payverse.user.dto;

import com.payverse.user.entity.KycTier;

import java.util.UUID;

public record InternalUserResponse(UUID userId, String fullName, KycTier kycTier, String mobileMasked) {
}
