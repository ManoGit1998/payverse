package com.payverse.wallet.dto;

import com.payverse.wallet.entity.KycTier;
import com.payverse.wallet.entity.WalletStatus;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(UUID walletId, UUID userId, MoneyDto balance, WalletStatus status, String statusReason,
                             KycTier kycTier, LimitsResponse limits, Instant updatedAt) {
}
