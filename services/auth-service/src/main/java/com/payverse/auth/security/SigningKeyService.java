package com.payverse.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.payverse.auth.entity.SigningKey;
import com.payverse.auth.entity.SigningKeyStatus;
import com.payverse.auth.repository.SigningKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RS256 signing keys persisted in {@code signing_keys}. The newest ACTIVE key signs; ACTIVE keys and
 * keys retired within {@link #RETIRED_KEY_GRACE} are published in the JWKS so tokens signed before a
 * rotation stay verifiable. Cached in memory for {@link #CACHE_TTL}.
 */
@Component
public class SigningKeyService {

    static final Duration RETIRED_KEY_GRACE = Duration.ofDays(1);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

    private final SigningKeyRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    private volatile KeyCache cache;

    record KeyCache(RSAKey signingKey, JWKSet publicKeys, Instant loadedAt) {
    }

    public SigningKeyService(SigningKeyRepository repository, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public RSAKey signingKey() {
        return current().signingKey();
    }

    public JWKSet publicJwkSet() {
        return current().publicKeys();
    }

    public Map<String, Object> publicJwks() {
        return publicJwkSet().toJSONObject();
    }

    /** JWK source for this service's own resource-server decoder (no HTTP round trip to itself). */
    public JWKSource<SecurityContext> jwkSource() {
        return (selector, context) -> selector.select(publicJwkSet());
    }

    /** Retires the active key(s) and creates a new one. */
    public String rotate() {
        String kid = tx.execute(status -> {
            Instant now = clock.instant();
            repository.findByStatus(SigningKeyStatus.ACTIVE).forEach(key -> key.retire(now));
            return createKey(now);
        });
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
        return tx.execute(status -> {
            Instant now = clock.instant();
            List<SigningKey> keys = publishable(now);
            if (keys.stream().noneMatch(SigningKey::isActive)) {
                createKey(now);
                keys = publishable(now);
            }
            List<JWK> published = new ArrayList<>();
            RSAKey signing = null;
            for (SigningKey key : keys.stream().sorted(Comparator.comparing(SigningKey::getCreatedAt)).toList()) {
                RSAKey rsa = parse(key.getPrivateJwk());
                published.add(rsa.toPublicJWK());
                if (key.isActive()) {
                    signing = rsa; // sorted ascending: the newest active key wins
                }
            }
            return new KeyCache(signing, new JWKSet(published), now);
        });
    }

    private List<SigningKey> publishable(Instant now) {
        return repository.findByStatusOrRetiredAtAfter(SigningKeyStatus.ACTIVE, now.minus(RETIRED_KEY_GRACE));
    }

    private String createKey(Instant now) {
        try {
            String kid = UUID.randomUUID().toString();
            RSAKey key = new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
            repository.saveAndFlush(SigningKey.active(kid, key.toPublicJWK().toJSONString(), key.toJSONString(), now));
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
