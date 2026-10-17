package com.payverse.notification.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.notification.entity.Channel;
import com.payverse.notification.entity.Template;
import com.payverse.notification.exception.NotificationErrorCode;
import com.payverse.notification.repository.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Admin management of templates (changes apply to notifications enqueued afterwards). */
@Service
public class TemplateService {

    private final TemplateRepository templates;
    private final AuditLogger audit;
    private final Clock clock;

    public TemplateService(TemplateRepository templates, AuditLogger audit, Clock clock) {
        this.templates = templates;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Template> all() {
        return templates.findAllByOrderByCodeAscChannelAsc();
    }

    @Transactional
    public Template update(String actor, String code, Channel channel, String subject, String body, boolean active) {
        if (body == null || body.isBlank() || !TemplateRenderer.isWellFormed(body) || (subject != null && !TemplateRenderer.isWellFormed(subject))) {
            throw new PayverseException(NotificationErrorCode.NOTIFICATION_INVALID_TEMPLATE,
                    "Placeholders must look like {{name}} (letters and digits)");
        }
        Template template = templates.findByCodeAndChannel(code, channel)
                .orElseGet(() -> templates.save(Template.create(code, channel, subject, body, clock.instant())));
        template.update(subject, body, active, clock.instant());
        audit.record(actor, "TEMPLATE_UPDATED", "Template", template.getId().toString(),
                Map.of("code", code, "channel", channel.name(), "active", active));
        return template;
    }
}
