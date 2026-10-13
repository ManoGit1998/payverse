package com.payverse.common.web.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Deterministic HMAC-SHA256 "blind index" so encrypted columns (mobile, account number) can still be
 * looked up by equality without storing plaintext. Inputs are normalised (trimmed) before hashing.
 */
public class BlindIndexer {

    private final SecretKeySpec key;

    public BlindIndexer(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("Blind index key must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(key, "HmacSHA256");
    }

    public String index(String value) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
