package com.payverse.common.web.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param ttl     how long a completed response is replayed for the same key (default 24h)
 * @param lockTtl how long an in-flight claim blocks concurrent duplicates (default 30s)
 */
@ConfigurationProperties("payverse.idempotency")
public record IdempotencyProperties(Duration ttl, Duration lockTtl) {

    public IdempotencyProperties {
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
        lockTtl = lockTtl == null ? Duration.ofSeconds(30) : lockTtl;
    }
}
