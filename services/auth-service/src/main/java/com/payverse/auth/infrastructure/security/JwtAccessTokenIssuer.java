package com.payverse.auth.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.payverse.auth.application.AuthProperties;
import com.payverse.auth.application.port.AccessTokenIssuer;
import com.payverse.common.security.PayverseClaims;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Signs access tokens with the active RS256 key. Claims match what {@code common-security} validates. */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final SigningKeyService keys;
    private final AuthProperties properties;
    private final Clock clock;

    JwtAccessTokenIssuer(SigningKeyService keys, AuthProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(String subject, Set<String> roles, String deviceId, Duration ttl) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(subject)
                .audience(PayverseClaims.DEFAULT_AUDIENCE)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim(PayverseClaims.ROLES, List.copyOf(roles.stream().sorted().toList()))
                .claim(PayverseClaims.TOKEN_TYPE, PayverseClaims.TOKEN_TYPE_ACCESS);
        if (deviceId != null) {
            claims.claim(PayverseClaims.DEVICE_ID, deviceId);
        }
        RSAKey key = keys.signingKey();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return new IssuedToken(jwt.serialize(), expiresAt);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign access token", e);
        }
    }
}
