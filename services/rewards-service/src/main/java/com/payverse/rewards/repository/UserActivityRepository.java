package com.payverse.rewards.repository;

import com.payverse.rewards.entity.RuleTrigger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Per-user count of qualifying events by trigger (drives first-time-only rules). */
@Repository
public class UserActivityRepository {

    private final JdbcTemplate jdbc;

    public UserActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the count including this event (1 = first time) */
    public long increment(UUID userId, RuleTrigger trigger) {
        Long count = jdbc.queryForObject("""
                INSERT INTO user_activity (user_id, trigger_type, event_count) VALUES (?, ?, 1)
                ON CONFLICT (user_id, trigger_type) DO UPDATE SET event_count = user_activity.event_count + 1
                RETURNING event_count
                """, Long.class, userId, trigger.name());
        return count == null ? 0 : count;
    }
}
