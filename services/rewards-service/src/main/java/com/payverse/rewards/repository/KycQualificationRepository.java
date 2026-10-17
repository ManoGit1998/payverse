package com.payverse.rewards.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Remembers that a user reached MIN_KYC / FULL_KYC (first time wins). */
@Repository
public class KycQualificationRepository {

    private final JdbcTemplate jdbc;

    public KycQualificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID userId, String tier, Instant at) {
        jdbc.update("INSERT INTO kyc_qualified_users (user_id, tier, qualified_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                userId, tier, Timestamp.from(at));
    }

    public boolean isQualified(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kyc_qualified_users WHERE user_id = ?", Integer.class, userId);
        return n != null && n > 0;
    }
}
