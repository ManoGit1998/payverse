package com.payverse.common.web.config;

import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.common.web.crypto.EncryptedStringConverter;
import com.payverse.common.web.crypto.FieldEncryptor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Field-level encryption + blind index, enabled when keys are configured. */
@AutoConfiguration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("payverse.crypto.encryption-key")
    FieldEncryptor fieldEncryptor(CryptoProperties properties) {
        Map<String, byte[]> previous = new HashMap<>();
        properties.previousKeys().forEach((id, key) -> previous.put(id, Base64.getDecoder().decode(key)));
        return new FieldEncryptor(properties.keyId(), Base64.getDecoder().decode(properties.encryptionKey()), previous);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("payverse.crypto.blind-index-key")
    BlindIndexer blindIndexer(CryptoProperties properties) {
        return new BlindIndexer(Base64.getDecoder().decode(properties.blindIndexKey()));
    }

    /** Wires the encryptor into the JPA converter — only in services that use JPA. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.persistence.AttributeConverter")
    static class JpaConverterRegistration {

        // ObjectProvider rather than @ConditionalOnBean: nested classes are evaluated before the
        // outer class's @Bean methods are registered, so the condition would never match.
        @Bean
        InitializingBean encryptedStringConverterRegistrar(ObjectProvider<FieldEncryptor> encryptor) {
            return () -> encryptor.ifAvailable(EncryptedStringConverter::register);
        }
    }
}
