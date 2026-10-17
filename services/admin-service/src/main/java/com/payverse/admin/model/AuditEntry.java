package com.payverse.admin.model;

import java.time.Instant;

/** One audit-log row ({@code details} is the JSON recorded with the action). */
public record AuditEntry(String id, String actor, String action, String entityType, String entityId, String details, String correlationId,
                         Instant createdAt) {
}
