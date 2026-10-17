package com.payverse.fraud.messaging;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.outbox.ProcessedEventGuard;
import com.payverse.fraud.service.RiskSignalService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Feeds risk signals: new devices, PIN locks and completed transfers (each event once). */
@Component
public class RiskSignalListeners {

    private static final String GROUP = "fraud-risk-service";

    private final EventEnvelopeCodec codec;
    private final ProcessedEventGuard guard;
    private final RiskSignalService signals;

    public RiskSignalListeners(EventEnvelopeCodec codec, ProcessedEventGuard guard, RiskSignalService signals) {
        this.codec = codec;
        this.guard = guard;
        this.signals = signals;
    }

    @KafkaListener(topics = Topics.AUTH_NEW_DEVICE_LOGIN, groupId = GROUP)
    public void onNewDeviceLogin(String message) {
        EventEnvelope<AuthEvents.NewDeviceLogin> envelope = codec.decode(message, AuthEvents.NewDeviceLogin.class);
        guard.handleOnce(envelope, "fraud-risk-service.devices",
                e -> signals.newDevice(e.payload().userId(), e.payload().deviceId(), e.occurredAt()));
    }

    @KafkaListener(topics = Topics.AUTH_PIN_LOCKED, groupId = GROUP)
    public void onPinLocked(String message) {
        EventEnvelope<AuthEvents.PinLocked> envelope = codec.decode(message, AuthEvents.PinLocked.class);
        guard.handleOnce(envelope, "fraud-risk-service.pin-locks", e -> signals.pinLocked(e.payload().userId(), e.occurredAt()));
    }

    @KafkaListener(topics = Topics.TRANSACTION_COMPLETED, groupId = GROUP)
    public void onTransactionCompleted(String message) {
        EventEnvelope<TransactionEvents.TransactionCompleted> envelope = codec.decode(message, TransactionEvents.TransactionCompleted.class);
        guard.handleOnce(envelope, "fraud-risk-service.baseline",
                e -> signals.transferCompleted(e.payload().payerUserId(), e.payload().amount().value()));
    }
}
