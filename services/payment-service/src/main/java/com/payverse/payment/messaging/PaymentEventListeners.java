package com.payverse.payment.messaging;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.outbox.ProcessedEventGuard;
import com.payverse.payment.service.CollectRequestService;
import com.payverse.payment.service.SettlementService;
import com.payverse.payment.service.VpaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Default VPA on registration; switch settlement + collect outcomes from transaction events. */
@Component
public class PaymentEventListeners {

    private final EventEnvelopeCodec codec;
    private final ProcessedEventGuard guard;
    private final VpaService vpaService;
    private final SettlementService settlements;
    private final CollectRequestService collects;

    public PaymentEventListeners(EventEnvelopeCodec codec, ProcessedEventGuard guard, VpaService vpaService,
                                 SettlementService settlements, CollectRequestService collects) {
        this.codec = codec;
        this.guard = guard;
        this.vpaService = vpaService;
        this.settlements = settlements;
        this.collects = collects;
    }

    @KafkaListener(topics = Topics.AUTH_USER_REGISTERED, groupId = "payment-service")
    public void onUserRegistered(String message) {
        EventEnvelope<AuthEvents.UserRegistered> envelope = codec.decode(message, AuthEvents.UserRegistered.class);
        guard.handleOnce(envelope, "payment-service.default-vpa", e -> vpaService.ensureDefault(e.payload().userId()));
    }

    @KafkaListener(topics = Topics.TRANSACTION_COMPLETED, groupId = "payment-service")
    public void onTransactionCompleted(String message) {
        EventEnvelope<TransactionEvents.TransactionCompleted> envelope = codec.decode(message, TransactionEvents.TransactionCompleted.class);
        guard.handleOnce(envelope, "payment-service.settlement", e -> {
            var p = e.payload();
            settlements.recordSettlement(p.transactionId(), p.payerUserId(), p.payeeUserId(), p.amount().toMoney());
            collects.onTransferOutcome(p.transactionId(), true);
        });
    }

    @KafkaListener(topics = Topics.TRANSACTION_FAILED, groupId = "payment-service")
    public void onTransactionFailed(String message) {
        EventEnvelope<TransactionEvents.TransactionFailed> envelope = codec.decode(message, TransactionEvents.TransactionFailed.class);
        guard.handleOnce(envelope, "payment-service.collect-failed", e -> collects.onTransferOutcome(e.payload().transactionId(), false));
    }
}
