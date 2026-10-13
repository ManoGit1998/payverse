package com.payverse.wallet.service;

import com.payverse.common.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MOCK payment gateway: approves every charge instantly <b>except amounts ending in .13</b>, which
 * are declined (use e.g. 100.13 to test the failure path). Replaced by payment-service in Phase 4.
 */
@Component
public class MockPaymentGatewayAdapter implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGatewayAdapter.class);

    public MockPaymentGatewayAdapter() {
        log.warn("MockPaymentGatewayAdapter (MOCK) is active: add-money charges are simulated.");
    }

    @Override
    public ChargeResult charge(UUID userId, Money amount, String method, String idempotencyKey) {
        if (amount.toMinorUnits() % 100 == 13) {
            log.info("[MOCK PG] declined {} via {}", amount, method);
            return new ChargeResult.Declined("Card declined by issuer (mock rule: amounts ending in .13)");
        }
        String reference = "MOCKPG-" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString().substring(0, 18).toUpperCase();
        log.info("[MOCK PG] charged {} via {} ref {}", amount, method, reference);
        return new ChargeResult.Succeeded(reference);
    }
}
