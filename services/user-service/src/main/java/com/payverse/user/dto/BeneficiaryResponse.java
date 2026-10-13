package com.payverse.user.dto;

import com.payverse.user.entity.BeneficiaryType;

import java.time.Instant;
import java.util.UUID;

/** {@code value} is masked for mobiles and bank accounts. */
public record BeneficiaryResponse(UUID id, BeneficiaryType type, String value, String ifsc, String nickname, Instant createdAt) {
}
