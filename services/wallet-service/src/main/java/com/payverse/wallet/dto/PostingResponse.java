package com.payverse.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record PostingResponse(UUID journalId, UUID walletId, UUID userId, MoneyDto balance, boolean replayed, Instant postedAt) {

    public static PostingResponse of(PostingResult result) {
        return new PostingResponse(result.journalId(), result.walletId(), result.userId(), MoneyDto.of(result.balanceAfter()),
                result.replayed(), result.postedAt());
    }
}
