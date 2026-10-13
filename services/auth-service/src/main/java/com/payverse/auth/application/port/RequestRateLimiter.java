package com.payverse.auth.application.port;

import java.time.Duration;

/** Fixed-window counter used for per-mobile OTP limits. */
public interface RequestRateLimiter {

    /** @return true if the call is allowed (counter incremented), false if the limit is reached */
    boolean tryAcquire(String key, int limit, Duration window);
}
