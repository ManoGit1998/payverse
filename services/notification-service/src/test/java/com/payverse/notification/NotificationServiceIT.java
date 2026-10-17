package com.payverse.notification;

import com.jayway.jsonpath.JsonPath;
import com.payverse.common.events.Amount;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.test.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Postgres/Redis/Kafka. EMAIL is configured to always fail with one attempt, so e-mail
 * notifications go DEAD immediately while SMS/PUSH are SENT.
 */
@SpringBootTest(properties = {"payverse.notification.mock-failing-channels=EMAIL", "payverse.notification.max-attempts=1",
        "payverse.notification.dispatch-interval-ms=200"})
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, NotificationServiceIT.TestTokens.class})
class NotificationServiceIT {

    @TestConfiguration
    static class TestTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return PayverseJwtValidators.configure((NimbusJwtDecoder) TestJwts.INSTANCE.jwtDecoder(), TestJwts.ISSUER, "payverse");
        }
    }

    static final String ADMIN = "Bearer " + TestJwts.INSTANCE.token("admin-1", List.of("ADMIN"));
    static final String SUPPORT = "Bearer " + TestJwts.INSTANCE.token("support-1", List.of("SUPPORT"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EventEnvelopeCodec codec;

    @Test
    void completedTransferNotifiesBothSidesAndShowsInTheInbox() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        var payload = new TransactionEvents.TransactionCompleted(UUID.randomUUID(), "P2P", payer, payee,
                new Amount(new BigDecimal("250.00"), "INR"), Instant.now());
        EventEnvelope<?> envelope = EventEnvelope.create(EventType.TRANSACTION_COMPLETED, "it", payload.transactionId().toString(), null, payload);
        String json = codec.encode(envelope);
        kafka.send(Topics.TRANSACTION_COMPLETED, envelope.aggregateId(), json).get();
        kafka.send(Topics.TRANSACTION_COMPLETED, envelope.aggregateId(), json).get(); // redelivery

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(statuses(payer)).containsExactly("SENT");
            assertThat(statuses(payee)).containsExactlyInAnyOrder("SENT", "SENT"); // SMS + PUSH
        });

        mvc.perform(get("/api/v1/notifications/me").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].body").value(org.hamcrest.Matchers.containsString("INR 250.00")));

        String id = jdbc.queryForObject("SELECT id::text FROM notifications WHERE user_id = ? LIMIT 1", String.class, payee);
        mvc.perform(post("/api/v1/notifications/me/{id}/read", id).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
        mvc.perform(post("/api/v1/notifications/me/{id}/read", id).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void failingChannelGoesDeadAndAdminCanRequeueIt() throws Exception {
        UUID user = UUID.randomUUID();
        var payload = new AuthEvents.UserRegistered(user, "98XXXXXX10", "REF");
        EventEnvelope<?> envelope = EventEnvelope.create(EventType.USER_REGISTERED, "it", user.toString(), null, payload);
        kafka.send(Topics.AUTH_USER_REGISTERED, user.toString(), codec.encode(envelope)).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(statuses(user)).containsExactlyInAnyOrder("SENT", "DEAD"));
        String dead = jdbc.queryForObject("SELECT id::text FROM notifications WHERE user_id = ? AND status = 'DEAD'", String.class, user);
        assertThat(jdbc.queryForObject("SELECT last_error FROM notifications WHERE id = ?::uuid", String.class, dead))
                .contains("MockEmailProvider");

        mvc.perform(post("/api/v1/admin/notifications/{id}/retry", dead).header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/notifications/{id}/retry", dead).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT attempts FROM notifications WHERE id = ?::uuid", Integer.class, dead)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'NOTIFICATION_RETRIED' AND entity_id = ?",
                Long.class, dead)).isEqualTo(1);
    }

    @Test
    void adminCanEditTemplates() throws Exception {
        String code = "IT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        mvc.perform(put("/api/v1/admin/templates/{code}/PUSH", code).header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"Hi\",\"body\":\"Amount {{amount}}\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mvc.perform(put("/api/v1/admin/templates/{code}/PUSH", code).header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Broken {{amount\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTIFICATION_INVALID_TEMPLATE"));
        mvc.perform(get("/api/v1/admin/templates").header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.code == '" + code + "')].body").value("Amount {{amount}}"));
        mvc.perform(get("/api/v1/admin/templates").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void poisonMessageIsDeadLetteredCapturedAndReplayable() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        kafka.send(Topics.AUTH_PIN_LOCKED, key, "{not json").get();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events WHERE message_key = ?", Long.class, key)).isEqualTo(1));
        String id = jdbc.queryForObject("SELECT id::text FROM dead_letter_events WHERE message_key = ?", String.class, key);
        assertThat(jdbc.queryForObject("SELECT original_topic FROM dead_letter_events WHERE id = ?::uuid", String.class, id))
                .isEqualTo(Topics.AUTH_PIN_LOCKED);
        assertThat(jdbc.queryForObject("SELECT error_message FROM dead_letter_events WHERE id = ?::uuid", String.class, id))
                .startsWith("[notification-service]");

        String list = mvc.perform(get("/api/v1/admin/dead-letters?size=100").header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).contains(id);

        mvc.perform(post("/api/v1/admin/dead-letters/{id}/replay", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayedAt").isNotEmpty());
        mvc.perform(post("/api/v1/admin/dead-letters/{id}/replay", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTIFICATION_ALREADY_REPLAYED"));
        // the replayed poison message fails again and is captured a second time (new offset)
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events WHERE message_key = ?", Long.class, key)).isEqualTo(2));
    }

    private List<String> statuses(UUID user) {
        return jdbc.queryForList("SELECT status FROM notifications WHERE user_id = ?", String.class, user);
    }
}
