package com.payverse.transaction.entity;

/** Progress of the transfer saga (each step is an idempotent wallet-service call). */
public enum SagaStep {
    /** Nothing moved yet. */
    NOT_STARTED,
    /** Payer debited into TRANSFER_SUSPENSE; next: credit the payee. */
    DEBITED,
    /** Payee credit was refused; next: reverse the payer's debit. */
    COMPENSATING,
    /** Payee credited — done. */
    CREDITED,
    /** Payer refunded — done. */
    COMPENSATED
}
