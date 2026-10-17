package com.payverse.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One rendered message for one user on one channel. Delivery retries with exponential back-off
 * (5s, 10s, 20s, …); after {@code maxAttempts} failures it becomes DEAD.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "template_code", nullable = false)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    private String subject;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    public static Notification pending(UUID userId, UUID eventId, String templateCode, Channel channel, String subject, String body,
                                       Instant now) {
        Notification n = new Notification();
        n.id = UUID.randomUUID();
        n.userId = userId;
        n.eventId = eventId;
        n.templateCode = templateCode;
        n.channel = channel;
        n.subject = subject;
        n.body = body;
        n.status = NotificationStatus.PENDING;
        n.nextAttemptAt = now;
        n.createdAt = now;
        return n;
    }

    public boolean isDue(Instant now) {
        return status == NotificationStatus.PENDING && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
    }

    public void markSent(String reference, Instant now) {
        attempts++;
        status = NotificationStatus.SENT;
        providerRef = reference;
        sentAt = now;
        nextAttemptAt = null;
        lastError = null;
    }

    /** @return true if this failure exhausted the attempts (now DEAD) */
    public boolean recordFailure(String error, int maxAttempts, Instant now) {
        attempts++;
        lastError = error == null ? null : error.length() <= 300 ? error : error.substring(0, 300);
        if (attempts >= maxAttempts) {
            status = NotificationStatus.DEAD;
            nextAttemptAt = null;
            return true;
        }
        nextAttemptAt = now.plus(Duration.ofSeconds(5L << Math.min(attempts - 1, 10)));
        return false;
    }

    /** An admin retry of a DEAD notification. */
    public void retry(Instant now) {
        if (status == NotificationStatus.DEAD) {
            status = NotificationStatus.PENDING;
            attempts = 0;
            nextAttemptAt = now;
        }
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }
}
