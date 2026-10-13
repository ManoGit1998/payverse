package com.payverse.common.web.audit;

import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.web.idempotency.IdempotencyTestApplication;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = IdempotencyTestApplication.class, properties = {
        "spring.application.name=common-web",
        "spring.main.web-application-type=none",
        "spring.flyway.locations=classpath:db/common"
})
@Import({PostgresTestcontainer.class, RedisTestcontainer.class})
class AuditLoggerIT {

    @Autowired
    AuditLogger audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordsAreWrittenAndCannotBeChanged() {
        String entityId = UUID.randomUUID().toString();
        MDC.put("correlationId", "corr-audit");
        try {
            audit.record("admin-1", "WALLET_FROZEN", "Wallet", entityId, Map.of("reason", "fraud review"));
        } finally {
            MDC.clear();
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT actor, action, details::text AS details, correlation_id FROM audit_log WHERE entity_id = ?", entityId);
        assertThat(row).containsEntry("actor", "admin-1").containsEntry("action", "WALLET_FROZEN").containsEntry("correlation_id", "corr-audit");
        assertThat((String) row.get("details")).contains("fraud review");

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'x' WHERE entity_id = ?", entityId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", entityId))
                .hasMessageContaining("append-only");
    }
}
