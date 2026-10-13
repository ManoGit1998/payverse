package com.payverse.user.dto;

import com.payverse.user.entity.KycTier;

import java.util.List;

public record KycStatusResponse(KycTier tier, KycTier nextTier, List<KycRecordResponse> history) {
}
