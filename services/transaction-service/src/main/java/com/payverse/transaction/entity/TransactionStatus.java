package com.payverse.transaction.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transaction state machine:
 * <pre>
 * INITIATED ──► PENDING ──► SUCCESS ──► REVERSED
 *     │            │
 *     └──► FAILED ◄┘
 * </pre>
 * FAILED and REVERSED are terminal.
 */
public enum TransactionStatus {
    INITIATED, PENDING, SUCCESS, FAILED, REVERSED;

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = Map.of(
            INITIATED, EnumSet.of(PENDING, FAILED),
            PENDING, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.of(REVERSED),
            FAILED, EnumSet.noneOf(TransactionStatus.class),
            REVERSED, EnumSet.noneOf(TransactionStatus.class));

    public boolean canMoveTo(TransactionStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** True once the saga has nothing left to do (SUCCESS can still be REVERSED later by a refund). */
    public boolean isSettled() {
        return this == SUCCESS || this == FAILED || this == REVERSED;
    }
}
