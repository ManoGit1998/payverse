package com.payverse.bill.service;

import com.payverse.bill.entity.Biller;
import com.payverse.bill.entity.BillPayment;
import com.payverse.bill.entity.MandateExecution;
import com.payverse.common.events.Amount;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.BillEvents;
import com.payverse.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** bill-payment-service events via the transactional outbox (call inside the business transaction). */
@Service
public class BillEventPublisher {

    private final OutboxPublisher outbox;

    public BillEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void completed(BillPayment p, Biller biller) {
        outbox.publish(EventType.BILL_PAYMENT_COMPLETED, p.getId().toString(), new BillEvents.BillPaymentCompleted(p.getId(), p.getUserId(),
                p.getCategory().name(), biller.getName(), Amount.of(p.getMoney()), p.getBillerRef()));
    }

    public void failed(BillPayment p) {
        outbox.publish(EventType.BILL_PAYMENT_FAILED, p.getId().toString(), new BillEvents.BillPaymentFailed(p.getId(), p.getUserId(),
                p.getCategory().name(), Amount.of(p.getMoney()), p.getFailureCode()));
    }

    public void mandateExecuted(MandateExecution e, UUID userId) {
        outbox.publish(EventType.MANDATE_EXECUTED, e.getMandateId().toString(), new BillEvents.MandateExecuted(e.getMandateId(), userId,
                e.getBillPaymentId(), e.getStatus().name()));
    }
}
