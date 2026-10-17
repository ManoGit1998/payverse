package com.payverse.notification.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.notification.config.NotificationProperties;
import com.payverse.notification.entity.Channel;
import com.payverse.notification.entity.Notification;
import com.payverse.notification.entity.NotificationStatus;
import com.payverse.notification.entity.Template;
import com.payverse.notification.repository.NotificationRepository;
import com.payverse.notification.repository.TemplateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServicesTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final TemplateRepository templates = mock(TemplateRepository.class);
    final NotificationRepository notifications = mock(NotificationRepository.class);
    final AuditLogger audit = mock(AuditLogger.class);

    @Test
    void enqueueRendersOneNotificationPerActiveChannelAndSkipsDuplicates() {
        UUID event = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(templates.findByCodeAndActiveTrue("MONEY_ADDED")).thenReturn(List.of(
                Template.create("MONEY_ADDED", Channel.PUSH, "Got {{amount}}", "INR {{amount}} added", NOW),
                Template.create("MONEY_ADDED", Channel.SMS, null, "INR {{amount}}", NOW)));
        when(notifications.existsByEventIdAndUserIdAndTemplateCodeAndChannel(event, user, "MONEY_ADDED", Channel.SMS)).thenReturn(true);
        when(notifications.save(any())).thenAnswer(i -> i.getArgument(0));

        List<Notification> created = new NotificationService(templates, notifications, clock)
                .enqueue(new NotificationService.NotificationRequest(event, user, "MONEY_ADDED", Map.of("amount", "5.00")));

        assertThat(created).singleElement().satisfies(n -> {
            assertThat(n.getChannel()).isEqualTo(Channel.PUSH);
            assertThat(n.getSubject()).isEqualTo("Got 5.00");
            assertThat(n.getBody()).isEqualTo("INR 5.00 added");
        });
    }

    @Test
    void enqueueWithoutTemplatesCreatesNothing() {
        when(templates.findByCodeAndActiveTrue("NOPE")).thenReturn(List.of());
        assertThat(new NotificationService(templates, notifications, clock)
                .enqueue(new NotificationService.NotificationRequest(UUID.randomUUID(), UUID.randomUUID(), "NOPE", Map.of()))).isEmpty();
    }

    @Test
    void dispatcherSendsDueAndRecordsFailures() {
        Notification ok = Notification.pending(UUID.randomUUID(), UUID.randomUUID(), "A", Channel.PUSH, null, "b", NOW);
        Notification failing = Notification.pending(UUID.randomUUID(), UUID.randomUUID(), "A", Channel.SMS, null, "b", NOW);
        Notification gone = Notification.pending(UUID.randomUUID(), UUID.randomUUID(), "A", Channel.PUSH, null, "b", NOW);
        gone.markSent("x", NOW);
        UUID missing = UUID.randomUUID();
        when(notifications.findDue(eq(NOW), any(Pageable.class))).thenReturn(List.of(ok.getId(), failing.getId(), gone.getId(), missing));
        when(notifications.lockById(ok.getId())).thenReturn(Optional.of(ok));
        when(notifications.lockById(failing.getId())).thenReturn(Optional.of(failing));
        when(notifications.lockById(gone.getId())).thenReturn(Optional.of(gone));
        when(notifications.lockById(missing)).thenReturn(Optional.empty());
        NotificationProperties props = new NotificationProperties(2, 10, Set.of(Channel.SMS));
        MockProviders mocks = new MockProviders();
        List<NotificationProvider> providers = List.of(mocks.mockSmsProvider(props), mocks.mockEmailProvider(props), mocks.mockPushProvider(props));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        NotificationDispatcher dispatcher = new NotificationDispatcher(notifications, providers, props, tm, new SimpleMeterRegistry(), clock);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(ok.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(ok.getProviderRef()).startsWith("push-");
        assertThat(failing.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(failing.getLastError()).contains("MockSmsProvider");

        when(notifications.findDue(any(), any(Pageable.class))).thenReturn(List.of(failing.getId()));
        NotificationDispatcher later = new NotificationDispatcher(notifications, providers, props, tm, new SimpleMeterRegistry(),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        assertThat(later.dispatchDue()).isZero();
        assertThat(failing.getStatus()).isEqualTo(NotificationStatus.DEAD);
    }

    @Test
    void inboxReadsMarksAndRetries() {
        UUID user = UUID.randomUUID();
        Notification n = Notification.pending(user, UUID.randomUUID(), "A", Channel.PUSH, null, "b", NOW);
        when(notifications.findById(n.getId())).thenReturn(Optional.of(n));
        when(notifications.findByUserIdOrderByCreatedAtDesc(eq(user), any())).thenReturn(new PageImpl<>(List.of(n)));
        when(notifications.countByUserIdAndReadAtIsNull(user)).thenReturn(1L);
        InboxService inbox = new InboxService(notifications, audit, clock);

        assertThat(inbox.inbox(user, 0, 20).getContent()).containsExactly(n);
        assertThat(inbox.unread(user)).isEqualTo(1);
        assertThat(inbox.markRead(user, n.getId()).getReadAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> inbox.markRead(UUID.randomUUID(), n.getId())).isInstanceOf(PayverseException.class);
        assertThatThrownBy(() -> inbox.retry("admin", UUID.randomUUID())).isInstanceOf(PayverseException.class);

        n.recordFailure("e", 1, NOW);
        assertThat(inbox.retry("admin", n.getId()).getStatus()).isEqualTo(NotificationStatus.PENDING);
        verify(audit).record(eq("admin"), eq("NOTIFICATION_RETRIED"), eq("Notification"), eq(n.getId().toString()), anyMap());
    }

    @Test
    void templatesAreValidatedUpdatedOrCreated() {
        Template existing = Template.create("A", Channel.SMS, null, "old", NOW);
        when(templates.findByCodeAndChannel("A", Channel.SMS)).thenReturn(Optional.of(existing));
        when(templates.findByCodeAndChannel("B", Channel.PUSH)).thenReturn(Optional.empty());
        when(templates.save(any())).thenAnswer(i -> i.getArgument(0));
        when(templates.findAllByOrderByCodeAscChannelAsc()).thenReturn(List.of(existing));
        TemplateService service = new TemplateService(templates, audit, clock);

        assertThat(service.all()).containsExactly(existing);
        assertThat(service.update("admin", "A", Channel.SMS, null, "new {{amount}}", false).getBody()).isEqualTo("new {{amount}}");
        assertThat(existing.isActive()).isFalse();
        assertThat(service.update("admin", "B", Channel.PUSH, "S", "body", true).getCode()).isEqualTo("B");
        assertThatThrownBy(() -> service.update("admin", "A", Channel.SMS, null, "bad {{x-y}}", true)).isInstanceOf(PayverseException.class);
        assertThatThrownBy(() -> service.update("admin", "A", Channel.SMS, "{{bad", "ok", true)).isInstanceOf(PayverseException.class);
        assertThatThrownBy(() -> service.update("admin", "A", Channel.SMS, null, " ", true)).isInstanceOf(PayverseException.class);
        verify(templates, never()).save(existing);
    }
}
