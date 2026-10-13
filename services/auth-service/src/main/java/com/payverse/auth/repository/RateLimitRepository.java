package com.payverse.auth.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/** Fixed-window counters in Redis (INCR; the expiry is set on the first hit of a window). */
@Repository
public class RateLimitRepository {

    private final StringRedisTemplate redis;

    public RateLimitRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return true if allowed (counter incremented), false once {@code limit} is reached in the window */
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = "ratelimit:" + key;
        Long count = redis.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            redis.expire(redisKey, window);
        }
        return count != null && count <= limit;
    }
}
