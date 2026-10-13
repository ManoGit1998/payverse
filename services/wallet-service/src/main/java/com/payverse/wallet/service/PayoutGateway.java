package com.payverse.wallet.service;

import com.payverse.common.money.Money;

import java.util.UUID;

/** Sends withdrawn money to the user's bank ({@link MockPayoutAdapter} locally). */
public interface PayoutGateway {

    /** @return the payout reference; throws when the bank rejects the payout */
    String initiate(UUID userId, UUID bankAccountId, Money amount, String idempotencyKey);
}
