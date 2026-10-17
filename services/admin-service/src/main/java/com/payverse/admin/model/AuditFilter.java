package com.payverse.admin.model;

import java.time.Instant;

/** Audit-log search; null fields are not filtered on. */
public record AuditFilter(String actor, String action, String entityType, String entityId, Instant from, Instant to) {
}
