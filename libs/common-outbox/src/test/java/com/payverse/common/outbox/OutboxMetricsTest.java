package com.payverse.common.outbox;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.WalletEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void countsEventsAndRecordsAmounts() {
        OutboxMetrics metrics = new OutboxMetrics(registry, jdbc);
        UUID user = UUID.randomUUID();
        var added = new WalletEvents.MoneyAdded(UUID.randomUUID(), user, new Amount(new BigDecimal("150.50"), "INR"), "pg", UUID.randomUUID());
        metrics.committed(EventEnvelope.create(EventType.MONEY_ADDED, "wallet-service", user.toString(), null, added));
        metrics.committed(EventEnvelope.create(EventType.MONEY_ADDED, "wallet-service", user.toString(), null, added));
        metrics.committed(EventEnvelope.create(EventType.USER_REGISTERED, "auth-service", user.toString(), null,
                new AuthEvents.UserRegistered(user, "******1234", null)));

        assertThat(registry.get("payverse.events").tag("event_type", "wallet.money-added").counter().count()).isEqualTo(2);
        assertThat(registry.get("payverse.events").tag("event_type", "auth.user-registered").counter().count()).isEqualTo(1);
        assertThat(registry.get("payverse.events.amount").tag("event_type", "wallet.money-added").summary().totalAmount()).isEqualTo(301.0);
        assertThat(registry.get("payverse.events.amount").tag("event_type", "auth.user-registered").summary().count()).isZero();
        // every event type starts at 0 so Prometheus increase() sees the first event too
        assertThat(registry.get("payverse.events").tag("event_type", "bill.payment-completed").counter().count()).isZero();
        assertThat(metrics.amount("not a record")).isEmpty();
    }

    @Test
    void backlogGaugesQueryTheOutboxAndToleratesFailures() {
        when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(7);
        new OutboxMetrics(registry, jdbc);
        assertThat(registry.get("payverse.outbox.pending").gauge().value()).isEqualTo(7);
        when(jdbc.queryForObject(anyString(), eq(Number.class))).thenThrow(new IllegalStateException("db down"));
        assertThat(registry.get("payverse.outbox.oldest.age").gauge().value()).isNaN();
    }
}
