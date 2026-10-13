package com.payverse.auth.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.payverse.auth.application.port.PublicKeyProvider;
import com.payverse.common.web.crypto.FieldEncryptor;
import com.nimbusds.jose.jwk.JWK;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * RS256 signing keys persisted in {@code signing_keys} (private key AES-GCM encrypted). The newest
 * ACTIVE key signs; ACTIVE keys and keys retired within {@link #RETIRED_KEY_GRACE} are published
 * in the JWKS so tokens signed before a rotation stay verifiable.
 */
@Component
public class SigningKeyService implements PublicKeyProvider {

    static final Duration RETIRED_KEY_GRACE = Duration.ofDays(1);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final Clock clock;

    private volatile KeyCache cache;

    record KeyCache(RSAKey signingKey, JWKSet publicKeys, Instant loadedAt) {
    }

    record StoredKey(String kid, String privateKeyEnc, String status, Instant createdAt, Instant retiredAt) {
    }

    public SigningKeyService(JdbcTemplate jdbc, FieldEncryptor encryptor, Clock clock) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    public RSAKey signingKey() {
        return current().signingKey();
    }

    public JWKSet publicJwkSet() {
        return current().publicKeys();
    }

    @Override
    public Map<String, Object> publicJwks() {
        return publicJwkSet().toJSONObject();
    }

    /** JWK source for this service's own resource-server decoder (no HTTP round trip to itself). */
    public JWKSource<SecurityContext> jwkSource() {
        return (selector, context) -> selector.select(publicJwkSet());
    }

    /** Retires the current active key(s) and creates a new one. */
    @Transactional
    public String rotate() {
        Instant now = clock.instant();
        jdbc.update("UPDATE signing_keys SET status = 'RETIRED', retired_at = ? WHERE status = 'ACTIVE'", Timestamp.from(now));
        String kid = insertNewKey(now);
        cache = null;
        log.info("Rotated JWT signing key; new kid {}", kid);
        return kid;
    }

    private KeyCache current() {
        KeyCache snapshot = cache;
        if (snapshot == null || snapshot.loadedAt().plus(CACHE_TTL).isBefore(clock.instant())) {
            snapshot = load();
            cache = snapshot;
        }
        return snapshot;
    }

    private synchronized KeyCache load() {
        Instant now = clock.instant();
        List<StoredKey> keys = query(now);
        if (keys.stream().noneMatch(k -> "ACTIVE".equals(k.status()))) {
            insertNewKey(now);
            keys = query(now);
        }
        List<JWK> parsed = new ArrayList<>();
        RSAKey signing = null;
        Instant newestActive = Instant.MIN;
        for (StoredKey key : keys.stream().sorted(Comparator.comparing(StoredKey::createdAt)).toList()) {
            RSAKey rsa = parse(encryptor.decrypt(key.privateKeyEnc()));
            parsed.add(rsa.toPublicJWK());
            if ("ACTIVE".equals(key.status()) && key.createdAt().isAfter(newestActive)) {
                newestActive = key.createdAt();
                signing = rsa;
            }
        }
        return new KeyCache(signing, new JWKSet(parsed), now);
    }

    private List<StoredKey> query(Instant now) {
        return jdbc.query("""
                        SELECT kid, private_key_enc, status, created_at, retired_at FROM signing_keys
                        WHERE status = 'ACTIVE' OR retired_at > ?
                        """,
                (rs, i) -> new StoredKey(rs.getString("kid"), rs.getString("private_key_enc"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("retired_at") == null ? null : rs.getTimestamp("retired_at").toInstant()),
                Timestamp.from(now.minus(RETIRED_KEY_GRACE)));
    }

    private String insertNewKey(Instant now) {
        try {
            String kid = UUID.randomUUID().toString();
            RSAKey key = new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
            jdbc.update("""
                            INSERT INTO signing_keys (kid, public_jwk, private_key_enc, status, created_at)
                            VALUES (?, ?, ?, 'ACTIVE', ?)
                            """,
                    kid, key.toPublicJWK().toJSONString(), encryptor.encrypt(key.toJSONString()), Timestamp.from(now));
            log.info("Generated new JWT signing key {}", kid);
            return kid;
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate RSA signing key", e);
        }
    }

    private static RSAKey parse(String json) {
        try {
            return RSAKey.parse(json);
        } catch (ParseException e) {
            throw new IllegalStateException("Corrupt signing key", e);
        }
    }
}
