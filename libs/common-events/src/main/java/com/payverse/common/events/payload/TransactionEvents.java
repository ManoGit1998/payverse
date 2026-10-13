package com.payverse.common.events.payload;

import com.payverse.common.events.Amount;

import java.time.Instant;
import java.util.UUID;

/** Events published by transaction-service. */
public sealed interface TransactionEvents {

    record TransactionInitiated(UUID transactionId, String type, UUID payerUserId, UUID payeeUserId, String payeeRef,
                                Amount amount) implements TransactionEvents {
    }

    record TransactionCompleted(UUID transactionId, String type, UUID payerUserId, UUID payeeUserId, Amount amount,
                                Instant completedAt) implements TransactionEvents {
    }

    record TransactionFailed(UUID transactionId, String type, UUID payerUserId, Amount amount, String reasonCode)
            implements TransactionEvents {
    }

    record TransactionReversed(UUID transactionId, UUID originalTransactionId, UUID payerUserId, UUID payeeUserId,
                               Amount amount, String reason) implements TransactionEvents {
    }

    record MoneyRequested(UUID requestId, UUID requesterUserId, UUID payerUserId, Amount amount, String note,
                          Instant expiresAt) implements TransactionEvents {
    }
}
