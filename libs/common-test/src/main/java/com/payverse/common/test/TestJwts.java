package com.payverse.common.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues RS256 test tokens shaped exactly like auth-service tokens, plus matching decoders/JWKS.
 * Use {@link #INSTANCE} so every test in a JVM shares one key pair.
 */
public final class TestJwts {

    public static final String ISSUER = "http://localhost:8081";
    public static final TestJwts INSTANCE = new TestJwts();

    private final RSAKey rsaKey;

    private TestJwts() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            this.rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("test-key")
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String userToken(UUID userId) {
        return token(userId.toString(), List.of("USER"), Map.of("device_id", "test-device"), Duration.ofMinutes(15));
    }

    public String token(String subject, List<String> roles) {
        return token(subject, roles, Map.of(), Duration.ofMinutes(15));
    }

    public String expiredToken(String subject) {
        return token(subject, List.of("USER"), Map.of(), Duration.ofMinutes(-5));
    }

    public String token(String subject, List<String> roles, Map<String, Object> extraClaims, Duration ttl) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience("payverse")
                .issueTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("typ", "access");
        extraClaims.forEach(claims::claim);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String bearer(UUID userId) {
        return "Bearer " + userToken(userId);
    }

    /** Public JWKS document, e.g. to serve from WireMock as auth-service's {@code /.well-known/jwks.json}. */
    public String jwksJson() {
        return new JWKSet(rsaKey.toPublicJWK()).toString();
    }

    public JwtDecoder jwtDecoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public ReactiveJwtDecoder reactiveJwtDecoder() {
        try {
            return NimbusReactiveJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
