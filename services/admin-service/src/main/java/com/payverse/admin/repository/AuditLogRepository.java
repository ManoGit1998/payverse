package com.payverse.admin.repository;

import com.payverse.admin.model.AuditEntry;
import com.payverse.admin.model.AuditFilter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Read side of the append-only {@code audit_log} (every admin action is recorded there). */
@Repository
public class AuditLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEntry> find(AuditFilter f, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        add(where, p, "actor", f.actor());
        add(where, p, "action", f.action());
        add(where, p, "entity_type", f.entityType());
        add(where, p, "entity_id", f.entityId());
        if (f.from() != null) {
            where.append(" AND created_at >= :from");
            p.addValue("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND created_at < :to");
            p.addValue("to", Timestamp.from(f.to()));
        }
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query("SELECT id::text, actor, action, entity_type, entity_id, details::text, correlation_id, created_at FROM audit_log"
                + where + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset", p, (rs, i) -> new AuditEntry(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()));
    }

    private static void add(StringBuilder where, MapSqlParameterSource p, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = :").append(column);
            p.addValue(column, value);
        }
    }
}
