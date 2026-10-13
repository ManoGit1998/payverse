package com.payverse.common.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * PII encryption keys (base64). Always supplied via environment variables, never committed.
 *
 * @param keyId          id of the active key, stored as ciphertext prefix
 * @param encryptionKey  base64 32-byte AES key ({@code PAYVERSE_CRYPTO_ENCRYPTION_KEY})
 * @param previousKeys   retired key id → base64 key, still accepted for decryption
 * @param blindIndexKey  base64 key (>= 32 bytes) for HMAC lookups ({@code PAYVERSE_CRYPTO_BLIND_INDEX_KEY})
 */
@ConfigurationProperties("payverse.crypto")
public record CryptoProperties(String keyId, String encryptionKey, Map<String, String> previousKeys, String blindIndexKey) {

    public CryptoProperties {
        keyId = keyId == null ? "v1" : keyId;
        previousKeys = previousKeys == null ? Map.of() : previousKeys;
    }
}
