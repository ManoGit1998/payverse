package com.payverse.transaction.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ensures only one runner (the request thread or the recovery job, on any instance) advances a given
 * saga at a time. Non-blocking: if another runner holds it, the caller simply skips.
 */
@Component
public class SagaLock {

    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    public SagaLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<String> tryLock(UUID transactionId) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(transactionId), token, LEASE))
                ? Optional.of(token) : Optional.empty();
    }

    public void unlock(UUID transactionId, String token) {
        redis.execute(RELEASE, List.of(key(transactionId)), token);
    }

    private static String key(UUID transactionId) {
        return "saga-lock:" + transactionId;
    }
}
