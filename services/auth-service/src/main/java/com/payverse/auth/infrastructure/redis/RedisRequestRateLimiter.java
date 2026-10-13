package com.payverse.auth.infrastructure.redis;

import com.payverse.auth.application.port.RequestRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Fixed-window counter: INCR, and set the expiry on the first hit of a window. */
@Component
class RedisRequestRateLimiter implements RequestRateLimiter {

    private final StringRedisTemplate redis;

    RedisRequestRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = "ratelimit:" + key;
        Long count = redis.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            redis.expire(redisKey, window);
        }
        return count != null && count <= limit;
    }
}
