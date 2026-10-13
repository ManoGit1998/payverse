package com.payverse.common.web.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * AES-256-GCM encryption for PII at rest. Ciphertext format: {@code <keyId>:<base64(iv || ciphertext+tag)>}.
 * Older key ids stay decryptable (pass them in {@code previousKeys}) so keys can be rotated.
 */
public class FieldEncryptor {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(String activeKeyId, byte[] activeKey, Map<String, byte[]> previousKeys) {
        Objects.requireNonNull(activeKeyId, "activeKeyId");
        if (activeKeyId.contains(":")) {
            throw new IllegalArgumentException("key id must not contain ':'");
        }
        this.activeKeyId = activeKeyId;
        var all = new java.util.HashMap<String, SecretKeySpec>();
        previousKeys.forEach((id, key) -> all.put(id, aesKey(key)));
        all.put(activeKeyId, aesKey(activeKey));
        this.keys = Map.copyOf(all);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return activeKeyId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        int sep = stored.indexOf(':');
        if (sep <= 0) {
            throw new IllegalArgumentException("Value is not in <keyId>:<payload> format");
        }
        String keyId = stored.substring(0, sep);
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Unknown encryption key id: " + keyId);
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(sep + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (tampered data or wrong key)", e);
        }
    }

    private static SecretKeySpec aesKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
        }
        return new SecretKeySpec(key, "AES");
    }
}
