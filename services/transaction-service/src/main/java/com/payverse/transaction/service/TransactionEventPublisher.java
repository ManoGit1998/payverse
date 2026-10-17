package com.payverse.transaction.service;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.transaction.entity.MoneyRequest;
import com.payverse.transaction.entity.Transaction;
import org.springframework.stereotype.Service;

/** transaction-service events via the transactional outbox (call inside the business transaction). */
@Service
public class TransactionEventPublisher {

    private final OutboxPublisher outbox;

    public TransactionEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void initiated(Transaction t) {
        outbox.publish(EventType.TRANSACTION_INITIATED, t.getId().toString(), new TransactionEvents.TransactionInitiated(
                t.getId(), t.getType().name(), t.getPayerUserId(), t.getPayeeUserId(), t.getPayeeRef(), Amount.of(t.getAmountMoney())));
    }

    public void completed(Transaction t) {
        outbox.publish(EventType.TRANSACTION_COMPLETED, t.getId().toString(), new TransactionEvents.TransactionCompleted(
                t.getId(), t.getType().name(), t.getPayerUserId(), t.getPayeeUserId(), Amount.of(t.getAmountMoney()), t.getCompletedAt()));
    }

    public void failed(Transaction t) {
        outbox.publish(EventType.TRANSACTION_FAILED, t.getId().toString(), new TransactionEvents.TransactionFailed(
                t.getId(), t.getType().name(), t.getPayerUserId(), Amount.of(t.getAmountMoney()), t.getFailureCode()));
    }

    public void moneyRequested(MoneyRequest r) {
        outbox.publish(EventType.MONEY_REQUESTED, r.getId().toString(), new TransactionEvents.MoneyRequested(
                r.getId(), r.getRequesterUserId(), r.getPayerUserId(), Amount.of(r.getAmountMoney()), r.getNote(), r.getExpiresAt()));
    }
}
