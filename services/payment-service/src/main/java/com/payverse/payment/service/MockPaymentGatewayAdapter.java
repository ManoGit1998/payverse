package com.payverse.payment.service;

import com.payverse.common.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MOCK payment gateway: approves every charge instantly <b>except amounts ending in .13</b>, which
 * are declined (use e.g. 100.13 to test the failure path).
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.payment-gateway.adapter", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGatewayAdapter implements PaymentGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGatewayAdapter.class);

    public MockPaymentGatewayAdapter() {
        log.warn("MockPaymentGatewayAdapter (MOCK) is active: add-money charges are simulated.");
    }

    @Override
    public String name() {
        return "MOCK_PG";
    }

    @Override
    public Charge charge(Money amount, String method, String idempotencyKey) {
        if (amount.toMinorUnits() % 100 == 13) {
            log.info("[MOCK PG] declined {} via {}", amount, method);
            return new Charge.Declined("Declined by issuer (mock rule: amounts ending in .13)");
        }
        String reference = "MOCKPG-" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8)).toString()
                .substring(0, 18).toUpperCase();
        log.info("[MOCK PG] charged {} via {} ref {}", amount, method, reference);
        return new Charge.Approved(reference);
    }
}
