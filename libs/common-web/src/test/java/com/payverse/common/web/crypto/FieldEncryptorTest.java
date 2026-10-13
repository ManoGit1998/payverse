package com.payverse.common.web.crypto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private final byte[] k1 = key();
    private final FieldEncryptor encryptor = new FieldEncryptor("v1", k1, Map.of());

    @AfterEach
    void reset() {
        EncryptedStringConverter.register(null);
    }

    @Test
    void roundTripsAndRandomisesIv() {
        String a = encryptor.encrypt("123456789012");
        String b = encryptor.encrypt("123456789012");
        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("123456789012");
        assertThat(encryptor.decrypt(a)).isEqualTo("123456789012");
        assertThat(encryptor.decrypt(b)).isEqualTo("123456789012");
    }

    @Test
    void nullsPassThrough() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void detectsTampering() {
        String ciphertext = encryptor.encrypt("secret");
        byte[] raw = java.util.Base64.getDecoder().decode(ciphertext.substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1:" + java.util.Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void supportsKeyRotation() {
        String oldCiphertext = encryptor.encrypt("old data");
        FieldEncryptor rotated = new FieldEncryptor("v2", key(), Map.of("v1", k1));
        assertThat(rotated.decrypt(oldCiphertext)).isEqualTo("old data");
        assertThat(rotated.encrypt("new data")).startsWith("v2:");
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> new FieldEncryptor("v1", new byte[16], Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldEncryptor("v:1", k1, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryptor.decrypt("no-separator")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryptor.decrypt("v9:AAAA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jpaConverterUsesRegisteredEncryptor() {
        EncryptedStringConverter converter = new EncryptedStringConverter();
        assertThatThrownBy(() -> converter.convertToDatabaseColumn("x")).isInstanceOf(IllegalStateException.class);

        EncryptedStringConverter.register(encryptor);
        String column = converter.convertToDatabaseColumn("9876543210");
        assertThat(column).startsWith("v1:");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo("9876543210");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void blindIndexIsDeterministicAndKeyed() {
        BlindIndexer indexer = new BlindIndexer(key());
        assertThat(indexer.index("9876543210")).hasSize(64).isEqualTo(indexer.index(" 9876543210 "));
        assertThat(indexer.index("9876543210")).isNotEqualTo(new BlindIndexer(key()).index("9876543210"));
        assertThat(indexer.index(null)).isNull();
        assertThatThrownBy(() -> new BlindIndexer(new byte[8])).isInstanceOf(IllegalArgumentException.class);
    }
}
