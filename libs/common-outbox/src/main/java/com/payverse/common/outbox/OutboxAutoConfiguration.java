package com.payverse.common.outbox;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Outbox publisher + relay, processed-events guard and DLT error handling for Kafka listeners. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JsonMapper.class)
    EventEnvelopeCodec eventEnvelopeCodec(JsonMapper jsonMapper) {
        return new EventEnvelopeCodec(jsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({JdbcTemplate.class, EventEnvelopeCodec.class})
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, EventEnvelopeCodec codec, OutboxProperties properties,
                                    Environment environment) {
        String producer = properties.producer() != null ? properties.producer()
                : environment.getProperty("spring.application.name", "unknown-service");
        return new OutboxPublisher(jdbc, codec, producer);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class, KafkaTemplate.class})
    @ConditionalOnProperty(name = "payverse.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        return new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), kafkaTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
    ProcessedEventGuard processedEventGuard(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new ProcessedEventGuard(jdbc, new TransactionTemplate(transactionManager));
    }

    /**
     * Retries a failing record 3 times with exponential back-off (1s, 2s, 4s), then publishes it to
     * {@code <topic>.dlt}. Malformed messages go straight to the DLT.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(KafkaTemplate.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + com.payverse.common.events.Topics.DLT_SUFFIX, -1));
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxAttempts(3);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(DeserializationException.class, JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
