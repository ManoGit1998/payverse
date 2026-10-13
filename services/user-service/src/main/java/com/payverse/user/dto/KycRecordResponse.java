package com.payverse.user.dto;

import com.payverse.user.entity.IdDocumentType;
import com.payverse.user.entity.KycStatus;
import com.payverse.user.entity.KycTier;

import java.time.Instant;
import java.util.UUID;

public record KycRecordResponse(UUID id, KycTier tier, IdDocumentType documentType, String documentMasked,
                                KycStatus status, String reason, Instant createdAt) {
}
