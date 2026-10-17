package com.payverse.payment.service;

import com.payverse.common.money.Money;
import com.payverse.payment.entity.PgOrder;
import com.payverse.payment.entity.PgOrderStatus;
import com.payverse.payment.repository.PgOrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Charges for "add money", called by wallet-service. Idempotent per key: a retry returns the recorded
 * outcome instead of charging again. Every order publishes {@code payment.pg-charge-settled} (recon).
 */
@Service
public class PaymentGatewayService {

    private final PgOrderRepository orders;
    private final PaymentGatewayProvider provider;
    private final PaymentEventPublisher events;
    private final Clock clock;

    public PaymentGatewayService(PgOrderRepository orders, PaymentGatewayProvider provider, PaymentEventPublisher events, Clock clock) {
        this.orders = orders;
        this.provider = provider;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public PgOrder charge(UUID userId, Money amount, String method, String idempotencyKey) {
        var existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentGatewayProvider.Charge charge = provider.charge(amount, method, idempotencyKey);
        PgOrder order = switch (charge) {
            case PaymentGatewayProvider.Charge.Approved approved -> PgOrder.record(userId, idempotencyKey, amount, method,
                    provider.name(), PgOrderStatus.SUCCEEDED, approved.reference(), null, clock.instant());
            case PaymentGatewayProvider.Charge.Declined declined -> PgOrder.record(userId, idempotencyKey, amount, method,
                    provider.name(), PgOrderStatus.DECLINED, null, declined.reason(), clock.instant());
        };
        try {
            order = orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException raced) {
            return orders.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raced);
        }
        events.pgChargeSettled(order);
        return order;
    }
}
