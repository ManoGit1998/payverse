package com.payverse.user.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One KYC submission and its outcome (an append-only history). */
public record KycRecord(UUID id, UUID userId, KycTier tier, IdDocument document, String documentRef,
                        String documentSha256, Status status, String reason, Instant createdAt) {

    public enum Status { VERIFIED, REJECTED }

    public KycRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(tier);
        Objects.requireNonNull(document);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
    }

    public static KycRecord verified(UUID userId, KycTier tier, IdDocument document, String documentRef,
                                     String documentSha256, Instant now) {
        return new KycRecord(UUID.randomUUID(), userId, tier, document, documentRef, documentSha256, Status.VERIFIED, null, now);
    }

    public static KycRecord rejected(UUID userId, KycTier tier, IdDocument document, String reason, Instant now) {
        return new KycRecord(UUID.randomUUID(), userId, tier, document, null, null, Status.REJECTED, reason, now);
    }
}
