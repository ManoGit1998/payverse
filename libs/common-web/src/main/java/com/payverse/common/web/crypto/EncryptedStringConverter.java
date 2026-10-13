package com.payverse.common.web.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that transparently encrypts a String column, e.g.
 * {@code @Convert(converter = EncryptedStringConverter.class) private String accountNumber;}.
 * Uses the {@link FieldEncryptor} registered by {@code CryptoAutoConfiguration}.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static volatile FieldEncryptor encryptor;

    public static void register(FieldEncryptor fieldEncryptor) {
        encryptor = fieldEncryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : requireEncryptor().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : requireEncryptor().decrypt(dbData);
    }

    private static FieldEncryptor requireEncryptor() {
        FieldEncryptor current = encryptor;
        if (current == null) {
            throw new IllegalStateException("FieldEncryptor not configured: set payverse.crypto.encryption-key");
        }
        return current;
    }
}
