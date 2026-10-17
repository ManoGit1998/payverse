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

import java.time.Instant;
import java.util.UUID;

/** A message template for one event code and channel, with {@code {{placeholder}}} variables. */
@Entity
@Table(name = "templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Template {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Template create(String code, Channel channel, String subject, String body, Instant now) {
        Template template = new Template();
        template.id = UUID.randomUUID();
        template.code = code;
        template.channel = channel;
        template.subject = subject;
        template.body = body;
        template.active = true;
        template.updatedAt = now;
        return template;
    }

    public void update(String newSubject, String newBody, boolean isActive, Instant now) {
        subject = newSubject;
        body = newBody;
        active = isActive;
        updatedAt = now;
    }
}
