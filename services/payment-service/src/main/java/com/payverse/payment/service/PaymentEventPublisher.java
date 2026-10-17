package com.payverse.payment.service;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.PaymentEvents;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.payment.entity.CollectRequest;
import com.payverse.payment.entity.PgOrder;
import org.springframework.stereotype.Service;

/** payment-service events via the transactional outbox. */
@Service
public class PaymentEventPublisher {

    private final OutboxPublisher outbox;

    public PaymentEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void collectRequested(CollectRequest request) {
        outbox.publish(EventType.COLLECT_REQUESTED, request.getId().toString(), new PaymentEvents.CollectRequested(
                request.getId(), request.getPayerVpa(), request.getPayeeVpa(), request.getPayerUserId(),
                Amount.of(request.getAmountMoney()), request.getExpiresAt()));
    }

    public void pgChargeSettled(PgOrder order) {
        outbox.publish(EventType.PG_CHARGE_SETTLED, order.getId().toString(), new PaymentEvents.PgChargeSettled(
                order.getId(), order.getUserId(), Amount.of(order.getAmountMoney()), order.getProvider(), order.getProviderRef(),
                order.getStatus().name()));
    }
}
