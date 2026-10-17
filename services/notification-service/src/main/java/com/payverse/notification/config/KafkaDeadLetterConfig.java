package com.payverse.notification.config;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Container factory for the dead-letter desk: if capturing fails (e.g. the database is down) it
 * retries every 5 seconds forever rather than publishing to {@code .dlt.dlt}.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaDeadLetterConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> deadLetterContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5_000, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
