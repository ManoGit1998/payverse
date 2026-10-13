package com.payverse.wallet.entity;

import java.util.UUID;

/** Platform ledger accounts seeded by the V1 migration (fixed ids). */
public enum SystemAccount {

    /** Money received from the payment gateway for "add money" (asset). */
    PG_CLEARING("00000000-0000-0000-0000-000000000001"),
    /** Withdrawals owed to users' banks until the payout settles (liability). */
    PAYOUT_CLEARING("00000000-0000-0000-0000-000000000002"),
    /** P2P money in flight between the debit and credit saga steps (liability; nets to 0). */
    TRANSFER_SUSPENSE("00000000-0000-0000-0000-000000000003"),
    /** Cashback paid out (expense). */
    REWARDS_POOL("00000000-0000-0000-0000-000000000004"),
    /** Bill payments owed to billers (liability). */
    BILLER_SETTLEMENT("00000000-0000-0000-0000-000000000005"),
    /** Platform fees (income). */
    FEES_INCOME("00000000-0000-0000-0000-000000000006");

    private final UUID id;

    SystemAccount(String id) {
        this.id = UUID.fromString(id);
    }

    public UUID id() {
        return id;
    }
}
