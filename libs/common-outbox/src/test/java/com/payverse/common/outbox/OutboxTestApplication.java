package com.payverse.common.outbox;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.WalletEvents;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@SpringBootApplication
class OutboxTestApplication {

    /** Listener that always fails for wallets whose status is POISON, to exercise retry + DLT. */
    @Component
    static class StatusListener {

        final List<String> handled = new CopyOnWriteArrayList<>();
        private final EventEnvelopeCodec codec;
        private final ProcessedEventGuard guard;

        StatusListener(EventEnvelopeCodec codec, ProcessedEventGuard guard) {
            this.codec = codec;
            this.guard = guard;
        }

        @KafkaListener(topics = Topics.WALLET_STATUS_CHANGED, groupId = "outbox-it")
        void on(String message) {
            EventEnvelope<WalletEvents.WalletStatusChanged> envelope = codec.decode(message, WalletEvents.WalletStatusChanged.class);
            if ("POISON".equals(envelope.payload().status())) {
                throw new IllegalStateException("cannot handle poison");
            }
            guard.handleOnce(envelope, "status-listener", e -> handled.add(e.aggregateId()));
        }
    }
}
