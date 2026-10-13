package com.payverse.auth.model;

import java.time.Instant;

/** Outcome of a PIN check. */
public sealed interface PinVerification {

    record Valid() implements PinVerification {
    }

    record Incorrect(int remainingAttempts) implements PinVerification {
    }

    /** The PIN is locked; {@code justLocked} is true when this attempt triggered the lock. */
    record Locked(Instant until, boolean justLocked) implements PinVerification {
    }
}
