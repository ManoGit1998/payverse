package com.payverse.common.events.payload;

import java.util.UUID;

/** Events published by user-service. */
public sealed interface UserEvents {

    record KycUpgraded(UUID userId, String fromTier, String toTier) implements UserEvents {
    }

    record BankAccountLinked(UUID userId, UUID bankAccountId, String maskedAccountNumber, String ifsc) implements UserEvents {
    }
}
