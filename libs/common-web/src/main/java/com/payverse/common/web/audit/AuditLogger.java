package com.payverse.common.web.audit;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Writes to the append-only {@code audit_log} table (UPDATE/DELETE are rejected by a trigger) in the
 * caller's transaction, so an audited action and its audit row commit together.
 */
public class AuditLogger {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public AuditLogger(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param actor      who did it: a user id, a service client id or {@code system}
     * @param action     e.g. {@code WALLET_DEBIT}, {@code WALLET_FROZEN}
     * @param entityType e.g. {@code Wallet}
     * @param entityId   affected entity id
     * @param details    non-sensitive details (amounts, references, reasons) — never raw PII
     */
    public void record(String actor, String action, String entityType, String entityId, Map<String, ?> details) {
        jdbc.update("""
                        INSERT INTO audit_log (id, actor, action, entity_type, entity_id, details, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                UUID.randomUUID(), actor, action, entityType, entityId, jsonMapper.writeValueAsString(details),
                MDC.get("correlationId"));
    }
}
