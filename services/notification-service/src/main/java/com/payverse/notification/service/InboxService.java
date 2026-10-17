package com.payverse.notification.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.notification.entity.Notification;
import com.payverse.notification.exception.NotificationErrorCode;
import com.payverse.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** A user's notification inbox. */
@Service
public class InboxService {

    private final NotificationRepository notifications;
    private final AuditLogger audit;
    private final Clock clock;

    public InboxService(NotificationRepository notifications, AuditLogger audit, Clock clock) {
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Notification> inbox(UUID userId, int page, int size) {
        return notifications.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public long unread(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public Notification markRead(UUID userId, UUID notificationId) {
        Notification notification = notifications.findById(notificationId).filter(n -> n.getUserId().equals(userId))
                .orElseThrow(() -> new PayverseException(NotificationErrorCode.NOTIFICATION_NOT_FOUND, null));
        notification.markRead(clock.instant());
        return notification;
    }

    /** Admin: put a DEAD notification back in the queue. */
    @Transactional
    public Notification retry(String actor, UUID notificationId) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new PayverseException(NotificationErrorCode.NOTIFICATION_NOT_FOUND, null));
        notification.retry(clock.instant());
        audit.record(actor, "NOTIFICATION_RETRIED", "Notification", notificationId.toString(), Map.of());
        return notification;
    }
}
