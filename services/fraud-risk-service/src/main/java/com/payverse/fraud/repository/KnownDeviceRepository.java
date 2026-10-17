package com.payverse.fraud.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Devices that logged in to an existing account, with when they were first seen. */
@Repository
public class KnownDeviceRepository {

    private final JdbcTemplate jdbc;

    public KnownDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordFirstSeen(UUID userId, String deviceId, Instant at) {
        jdbc.update("INSERT INTO known_devices (user_id, device_id, first_seen_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                userId, deviceId, Timestamp.from(at));
    }

    public Optional<Instant> firstSeen(UUID userId, String deviceId) {
        List<Timestamp> rows = jdbc.queryForList("SELECT first_seen_at FROM known_devices WHERE user_id = ? AND device_id = ?",
                Timestamp.class, userId, deviceId);
        return rows.stream().findFirst().map(Timestamp::toInstant);
    }
}
