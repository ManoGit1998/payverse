package com.payverse.fraud.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Everything a rule may look at for one transaction. {@code velocity} answers "how many transfers
 * (and how much) in the last {@code window}, including this one".
 */
public record RiskContext(UUID transactionId, UUID userId, UUID payeeUserId, String type, BigDecimal amount, String deviceId,
                          Instant now, long completedCount, BigDecimal averageAmount, Optional<Instant> deviceFirstSeen,
                          Optional<Instant> lastPinLockAt, Function<Duration, VelocityWindow> velocity) {

    public record VelocityWindow(long count, BigDecimal total) {
    }
}
