package com.payverse.notification.service;

import com.payverse.notification.entity.Notification;
import com.payverse.notification.entity.Template;
import com.payverse.notification.repository.NotificationRepository;
import com.payverse.notification.repository.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Turns "notify user X about event Y with template Z" into one PENDING notification per active channel. */
@Service
public class NotificationService {

    public record NotificationRequest(UUID eventId, UUID userId, String templateCode, Map<String, String> vars) {
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final TemplateRepository templates;
    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationService(TemplateRepository templates, NotificationRepository notifications, Clock clock) {
        this.templates = templates;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Idempotent per (event, user, template, channel); joins the consumer's transaction. */
    @Transactional
    public List<Notification> enqueue(NotificationRequest request) {
        List<Template> active = templates.findByCodeAndActiveTrue(request.templateCode());
        if (active.isEmpty()) {
            log.warn("No active template for {}; nothing sent", request.templateCode());
        }
        List<Notification> created = new ArrayList<>();
        for (Template template : active) {
            if (notifications.existsByEventIdAndUserIdAndTemplateCodeAndChannel(request.eventId(), request.userId(),
                    template.getCode(), template.getChannel())) {
                continue;
            }
            created.add(notifications.save(Notification.pending(request.userId(), request.eventId(), template.getCode(),
                    template.getChannel(), TemplateRenderer.render(template.getSubject(), request.vars()),
                    TemplateRenderer.render(template.getBody(), request.vars()), clock.instant())));
        }
        return created;
    }
}
