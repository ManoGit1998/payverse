package com.payverse.wallet.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.wallet.exception.WalletErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MOCK bank payout (IMPS-style): accepts every payout <b>except amounts ending in .14</b>, which fail
 * (the whole withdrawal then rolls back). A real adapter would submit asynchronously via the outbox.
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.payout.adapter", havingValue = "mock", matchIfMissing = true)
public class MockPayoutAdapter implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPayoutAdapter.class);

    public MockPayoutAdapter() {
        log.warn("MockPayoutAdapter (MOCK) is active: bank payouts are simulated.");
    }

    @Override
    public String initiate(UUID userId, UUID bankAccountId, Money amount, String idempotencyKey) {
        if (amount.toMinorUnits() % 100 == 14) {
            log.info("[MOCK PAYOUT] rejected {}", amount);
            throw new PayverseException(WalletErrorCode.WALLET_PAYOUT_FAILED, "Beneficiary bank rejected the payout (mock rule)");
        }
        String reference = "MOCKIMPS-" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString().substring(0, 12).toUpperCase();
        log.info("[MOCK PAYOUT] sent {} ref {}", amount, reference);
        return reference;
    }
}
