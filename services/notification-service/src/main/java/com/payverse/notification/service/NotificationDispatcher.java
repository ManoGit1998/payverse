package com.payverse.notification.service;

import com.payverse.notification.config.NotificationProperties;
import com.payverse.notification.entity.Channel;
import com.payverse.notification.entity.Notification;
import com.payverse.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends due notifications. Each one is delivered under a row lock in its own transaction, so
 * several instances can run the dispatcher without double-sending. Failures back off and finally
 * go DEAD (visible to admins).
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository notifications;
    private final Map<Channel, NotificationProvider> providers = new EnumMap<>(Channel.class);
    private final NotificationProperties properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public NotificationDispatcher(NotificationRepository notifications, List<NotificationProvider> providerList,
                                  NotificationProperties properties, PlatformTransactionManager transactionManager,
                                  MeterRegistry meters, Clock clock) {
        this.notifications = notifications;
        providerList.forEach(p -> providers.put(p.channel(), p));
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.meters = meters;
        this.clock = clock;
        // series start at 0 so increase()/rate() count the first sends too (OTLP push)
        for (Channel channel : Channel.values()) {
            for (String outcome : new String[] {"SENT", "RETRY", "DEAD"}) {
                meters.counter("payverse.notifications", "channel", channel.name(), "outcome", outcome);
            }
        }
    }

    @Scheduled(fixedDelayString = "${payverse.notification.dispatch-interval-ms:1000}")
    public int dispatchDue() {
        List<UUID> due = notifications.findDue(clock.instant(), PageRequest.of(0, properties.dispatchBatch()));
        int sent = 0;
        for (UUID id : due) {
            if (Boolean.TRUE.equals(tx.execute(status -> deliver(id)))) {
                sent++;
            }
        }
        return sent;
    }

    private boolean deliver(UUID id) {
        Notification notification = notifications.lockById(id).orElse(null);
        Instant now = clock.instant();
        if (notification == null || !notification.isDue(now)) {
            return false; // another instance got it first
        }
        NotificationProvider provider = providers.get(notification.getChannel());
        try {
            String reference = provider.send(new NotificationProvider.Delivery(notification.getId(), notification.getUserId(),
                    notification.getSubject(), notification.getBody()));
            notification.markSent(reference, now);
            meters.counter("payverse.notifications", "channel", notification.getChannel().name(), "outcome", "SENT").increment();
            return true;
        } catch (RuntimeException e) {
            boolean dead = notification.recordFailure(e.getMessage(), properties.maxAttempts(), now);
            meters.counter("payverse.notifications", "channel", notification.getChannel().name(), "outcome", dead ? "DEAD" : "RETRY").increment();
            if (dead) {
                log.error("Notification {} ({} {}) is DEAD after {} attempts: {}", notification.getId(), notification.getChannel(),
                        notification.getTemplateCode(), notification.getAttempts(), e.getMessage());
            } else {
                log.warn("Notification {} failed (attempt {}), retrying at {}", notification.getId(), notification.getAttempts(),
                        notification.getNextAttemptAt());
            }
            return false;
        }
    }
}
