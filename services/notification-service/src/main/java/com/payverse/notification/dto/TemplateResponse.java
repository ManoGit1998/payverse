package com.payverse.notification.dto;

import com.payverse.notification.entity.Channel;

import java.time.Instant;
import java.util.UUID;

public record TemplateResponse(UUID id, String code, Channel channel, String subject, String body, boolean active,
                               Instant updatedAt) {
}
