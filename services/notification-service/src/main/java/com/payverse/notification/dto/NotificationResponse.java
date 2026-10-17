package com.payverse.notification.dto;

import com.payverse.notification.entity.Channel;
import com.payverse.notification.entity.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, String templateCode, Channel channel, String subject, String body,
                                   NotificationStatus status, int attempts, Instant createdAt, Instant sentAt, Instant readAt) {
}
