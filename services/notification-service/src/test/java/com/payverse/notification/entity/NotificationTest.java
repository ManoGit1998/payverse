package com.payverse.notification.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    static Notification pending() {
        return Notification.pending(UUID.randomUUID(), UUID.randomUUID(), "MONEY_ADDED", Channel.PUSH, "Money added", "INR 1.00", NOW);
    }

    @Test
    void newNotificationIsDueImmediately() {
        Notification n = pending();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.isDue(NOW)).isTrue();
        assertThat(n.isDue(NOW.minusSeconds(1))).isFalse();
    }

    @Test
    void sentClearsSchedulingAndRecordsReference() {
        Notification n = pending();
        n.recordFailure("boom", 5, NOW);
        n.markSent("push-1", NOW.plusSeconds(10));
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getAttempts()).isEqualTo(2);
        assertThat(n.getProviderRef()).isEqualTo("push-1");
        assertThat(n.getSentAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(n.getLastError()).isNull();
        assertThat(n.isDue(NOW.plusSeconds(60))).isFalse();
    }

    @Test
    void failuresBackOffExponentiallyThenGoDead() {
        Notification n = pending();
        assertThat(n.recordFailure("e1", 3, NOW)).isFalse();
        assertThat(n.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(5)));
        assertThat(n.recordFailure("e2", 3, NOW)).isFalse();
        assertThat(n.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(10)));
        assertThat(n.recordFailure("e3", 3, NOW)).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD);
        assertThat(n.getNextAttemptAt()).isNull();
        assertThat(n.getLastError()).isEqualTo("e3");
    }

    @Test
    void longErrorsAreTruncated() {
        Notification n = pending();
        n.recordFailure("x".repeat(500), 5, NOW);
        assertThat(n.getLastError()).hasSize(300);
        n.recordFailure(null, 5, NOW);
        assertThat(n.getLastError()).isNull();
    }

    @Test
    void retryRequeuesOnlyDeadNotifications() {
        Notification n = pending();
        n.recordFailure("e", 1, NOW);
        n.retry(NOW.plusSeconds(5));
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getAttempts()).isZero();
        assertThat(n.isDue(NOW.plusSeconds(5))).isTrue();

        Notification sent = pending();
        sent.markSent("r", NOW);
        sent.retry(NOW);
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void readTimestampIsSetOnce() {
        Notification n = pending();
        n.markRead(NOW);
        n.markRead(NOW.plusSeconds(1));
        assertThat(n.getReadAt()).isEqualTo(NOW);
    }
}
