package com.payverse.common.events;

import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.common.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventCatalogTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyEventTypeIsConsistent(EventType type) {
        assertThat(type.topic()).isEqualTo("payverse." + type.typeName() + ".v1");
        assertThat(type.payloadType().isRecord()).isTrue();
        assertThat(type.version()).isEqualTo(1);
        assertThat(EventType.fromTypeName(type.typeName())).isEqualTo(type);
        assertThat(EventType.fromTopic(type.topic())).isEqualTo(type);
    }

    @Test
    void everyTopicConstantHasAnEventType() {
        Set<String> constants = Arrays.stream(Topics.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && !f.getName().startsWith("DLT"))
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.toSet());
        Set<String> catalog = Arrays.stream(EventType.values()).map(EventType::topic).collect(Collectors.toSet());
        assertThat(catalog).isEqualTo(constants);
        assertThat(Topics.dlt(Topics.WALLET_CREATED)).isEqualTo("payverse.wallet.created.v1.dlt");
    }

    @Test
    void createValidatesPayloadType() {
        var payload = new WalletEvents.WalletCreated(UUID.randomUUID(), UUID.randomUUID(), "MIN_KYC");
        EventEnvelope<WalletEvents.WalletCreated> envelope =
                EventEnvelope.create(EventType.WALLET_CREATED, "wallet-service", payload.walletId().toString(), "corr-1", payload);
        assertThat(envelope.eventType()).isEqualTo("wallet.created");
        assertThat(envelope.aggregateType()).isEqualTo("Wallet");
        assertThat(envelope.topic()).isEqualTo(Topics.WALLET_CREATED);
        assertThat(envelope.version()).isEqualTo(1);

        assertThatThrownBy(() -> EventEnvelope.create(EventType.MONEY_ADDED, "wallet-service", "x", null, payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownNamesAreRejected() {
        assertThatThrownBy(() -> EventType.fromTypeName("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventType.fromTopic("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeRoundTripsThroughJsonWithExactMoney() {
        var payload = new TransactionEvents.TransactionCompleted(UUID.randomUUID(), "P2P", UUID.randomUUID(),
                UUID.randomUUID(), Amount.of(Money.inr("1234.50")), Instant.parse("2026-10-13T10:15:30Z"));
        var envelope = EventEnvelope.create(EventType.TRANSACTION_COMPLETED, "transaction-service",
                payload.transactionId().toString(), "corr-9", payload);

        String json = mapper.writeValueAsString(envelope);
        assertThat(json).contains("\"value\":1234.50").contains("\"currency\":\"INR\"");

        JavaType type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class,
                EventType.fromTypeName(envelope.eventType()).payloadType());
        EventEnvelope<?> back = mapper.readValue(json, type);
        assertThat(back).isEqualTo(envelope);
        assertThat(((TransactionEvents.TransactionCompleted) back.payload()).amount().toMoney()).isEqualTo(Money.inr("1234.50"));
    }

    @Test
    void ledgerPostedCarriesEntries() {
        var entries = List.of(
                new WalletEvents.LedgerPosted.Entry("WALLET:abc", "DEBIT", Amount.of(Money.inr("10"))),
                new WalletEvents.LedgerPosted.Entry("TRANSFER_SUSPENSE", "CREDIT", Amount.of(Money.inr("10"))));
        var posted = new WalletEvents.LedgerPosted(UUID.randomUUID(), "TRANSFER_DEBIT", "Transaction", "t-1", entries);
        assertThat(posted.entries()).hasSize(2);
    }
}
