package com.payverse.user.infrastructure.kyc;

import com.payverse.user.application.port.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * MOCK document store: records a reference and the SHA-256 of the upload but <b>discards the bytes</b>
 * (no PII files on developer laptops). Replace with an encrypted object store (S3 + KMS) in production.
 */
@Component
class MockDocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(MockDocumentStorage.class);

    @Override
    public StoredDocument store(UUID userId, byte[] content, String contentType) {
        try {
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            String reference = "mock-kyc://" + UUID.randomUUID();
            log.info("[MOCK STORAGE] KYC document of {} bytes ({}) recorded as {} — content discarded", content.length, contentType, reference);
            return new StoredDocument(reference, sha256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
