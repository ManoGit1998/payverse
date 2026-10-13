package com.payverse.auth.repository;

import com.payverse.auth.entity.SigningKey;
import com.payverse.auth.entity.SigningKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SigningKeyRepository extends JpaRepository<SigningKey, String> {

    List<SigningKey> findByStatus(SigningKeyStatus status);

    /** Keys to publish in the JWKS: active ones plus those retired after {@code cutoff}. */
    List<SigningKey> findByStatusOrRetiredAtAfter(SigningKeyStatus status, Instant cutoff);
}
