package com.payverse.fraud.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Sliding-window velocity per user in a Redis sorted set: member {@code <txnId>|<amount>}, score =
 * epoch millis. Recording is idempotent per transaction; entries older than the retention are trimmed.
 */
@Repository
public class VelocityStore {

    public record Window(long count, BigDecimal total) {
    }

    static final Duration RETENTION = Duration.ofDays(2);

    private final StringRedisTemplate redis;

    public VelocityStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void record(UUID userId, UUID transactionId, BigDecimal amount, Instant at) {
        String key = key(userId);
        redis.opsForZSet().add(key, transactionId + "|" + amount.toPlainString(), at.toEpochMilli());
        redis.opsForZSet().removeRangeByScore(key, 0, at.minus(RETENTION).toEpochMilli());
        redis.expire(key, RETENTION);
    }

    public Window window(UUID userId, Instant now, Duration window) {
        Set<String> members = redis.opsForZSet().rangeByScore(key(userId), now.minus(window).toEpochMilli(), now.toEpochMilli());
        if (members == null || members.isEmpty()) {
            return new Window(0, BigDecimal.ZERO);
        }
        BigDecimal total = members.stream().map(m -> new BigDecimal(m.substring(m.indexOf('|') + 1))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Window(members.size(), total);
    }

    static String key(UUID userId) {
        return "fraud:velocity:" + userId;
    }
}
