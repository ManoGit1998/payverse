package com.payverse.notification.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.notification.entity.DeadLetterEvent;
import com.payverse.notification.exception.NotificationErrorCode;
import com.payverse.notification.repository.DeadLetterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLetterServiceTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    final DeadLetterRepository repo = mock(DeadLetterRepository.class);
    @SuppressWarnings("unchecked")
    final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    final DeadLetterService service = new DeadLetterService(repo, kafka, mock(AuditLogger.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void captureIsIdempotentPerTopicPartitionOffset() {
        when(repo.existsByDltTopicAndPartitionNoAndOffsetNo("t.dlt", 0, 1)).thenReturn(false, true);
        service.capture("t.dlt", 0, 1, "k", "{}", "err");
        service.capture("t.dlt", 0, 1, "k", "{}", "err");
        verify(repo, times(1)).save(any());
    }

    @Test
    void listsOpenOrAll() {
        when(repo.findByReplayedAtIsNullOrderByReceivedAtDesc(any())).thenReturn(Page.empty());
        when(repo.findAllByOrderByReceivedAtDesc(any())).thenReturn(Page.empty());
        assertThat(service.list(true, 0, 10)).isEmpty();
        assertThat(service.list(false, 0, 10)).isEmpty();
        verify(repo).findAllByOrderByReceivedAtDesc(any());
    }

    @Test
    void replayRepublishesOnceToTheOriginalTopic() {
        DeadLetterEvent event = DeadLetterEvent.capture("payverse.x.v1.dlt", 0, 5, "k", "{\"a\":1}", "e", NOW);
        when(repo.findById(event.getId())).thenReturn(Optional.of(event));
        when(kafka.send("payverse.x.v1", "k", "{\"a\":1}")).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(service.replay("admin", event.getId()).getReplayedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.replay("admin", event.getId())).isInstanceOfSatisfying(PayverseException.class,
                e -> assertThat(e.errorCode()).isEqualTo(NotificationErrorCode.NOTIFICATION_ALREADY_REPLAYED));
        assertThatThrownBy(() -> service.replay("admin", UUID.randomUUID())).isInstanceOf(PayverseException.class);
    }

    @Test
    void replayFailsWhenKafkaIsDown() {
        DeadLetterEvent event = DeadLetterEvent.capture("payverse.x.v1.dlt", 0, 6, "k", "{}", "e", NOW);
        when(repo.findById(event.getId())).thenReturn(Optional.of(event));
        when(kafka.send(any(String.class), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        assertThatThrownBy(() -> service.replay("admin", event.getId())).isInstanceOf(PayverseException.class);
        assertThat(event.isReplayed()).isFalse();
        verify(repo, never()).save(any());
    }
}
