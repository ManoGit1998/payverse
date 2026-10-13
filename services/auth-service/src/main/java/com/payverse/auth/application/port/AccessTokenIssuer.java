package com.payverse.auth.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Issues signed (RS256) access tokens. */
public interface AccessTokenIssuer {

    record IssuedToken(String value, Instant expiresAt) {
    }

    IssuedToken issue(String subject, Set<String> roles, String deviceId, Duration ttl);
}
