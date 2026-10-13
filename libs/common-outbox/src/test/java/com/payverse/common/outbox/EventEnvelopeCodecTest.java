package com.payverse.common.outbox;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.common.money.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeCodecTest {

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(JsonMapper.builder().build());

    @Test
    void decodesPayloadTypeFromCatalog() {
        var payload = new WalletEvents.MoneyAdded(UUID.randomUUID(), UUID.randomUUID(), Amount.of(Money.inr("250.75")), "pg-1", UUID.randomUUID());
        var envelope = EventEnvelope.create(EventType.MONEY_ADDED, "wallet-service", payload.walletId().toString(), "c", payload);

        EventEnvelope<?> decoded = codec.decode(codec.encode(envelope));

        assertThat(decoded.payload()).isInstanceOf(WalletEvents.MoneyAdded.class).isEqualTo(payload);
    }

    @Test
    void rejectsNonEnvelopes() {
        assertThatThrownBy(() -> codec.decode("{\"foo\":1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"eventType\":\"nope\"}")).isInstanceOf(IllegalArgumentException.class);
    }
}
