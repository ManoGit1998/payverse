package com.payverse.auth.infrastructure.redis;

import com.payverse.auth.application.SecureTokens;
import com.payverse.auth.application.port.OtpStore;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpChallenge;
import com.payverse.auth.domain.OtpPurpose;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** OTP challenges in Redis, keyed by purpose + SHA-256 of the mobile (no plaintext PII in keys). */
@Component
class RedisOtpStore implements OtpStore {

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    RedisOtpStore(StringRedisTemplate redis, JsonMapper jsonMapper, Clock clock) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public void save(MobileNumber mobile, OtpChallenge challenge) {
        Duration ttl = Duration.between(clock.instant(), challenge.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            delete(mobile, challenge.purpose());
            return;
        }
        redis.opsForValue().set(key(mobile, challenge.purpose()), jsonMapper.writeValueAsString(challenge), ttl);
    }

    @Override
    public Optional<OtpChallenge> find(MobileNumber mobile, OtpPurpose purpose) {
        return Optional.ofNullable(redis.opsForValue().get(key(mobile, purpose)))
                .map(json -> jsonMapper.readValue(json, OtpChallenge.class));
    }

    @Override
    public void delete(MobileNumber mobile, OtpPurpose purpose) {
        redis.delete(key(mobile, purpose));
    }

    private static String key(MobileNumber mobile, OtpPurpose purpose) {
        return "otp:" + purpose.name().toLowerCase() + ":" + SecureTokens.sha256(mobile.value());
    }
}
