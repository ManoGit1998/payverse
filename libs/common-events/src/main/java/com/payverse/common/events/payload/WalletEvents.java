package com.payverse.common.events.payload;

import com.payverse.common.events.Amount;

import java.util.List;
import java.util.UUID;

/** Events published by wallet-service. */
public sealed interface WalletEvents {

    record WalletCreated(UUID walletId, UUID userId, String kycTier) implements WalletEvents {
    }

    record MoneyAdded(UUID walletId, UUID userId, Amount amount, String pgReference, UUID journalId) implements WalletEvents {
    }

    record MoneyWithdrawn(UUID walletId, UUID userId, Amount amount, UUID bankAccountId, String payoutReference,
                          UUID journalId) implements WalletEvents {
    }

    record WalletStatusChanged(UUID walletId, UUID userId, String status, String reason, String actor) implements WalletEvents {
    }

    record LedgerPosted(UUID journalId, String journalType, String referenceType, String referenceId,
                        List<Entry> entries) implements WalletEvents {

        public record Entry(String accountCode, String side, Amount amount) {
        }
    }
}
