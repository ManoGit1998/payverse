package com.payverse.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/** Reusable single-node Kafka (KRaft) container wired through {@code @ServiceConnection}. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainer {

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xmx384m -Xms128m")
            .withReuse(true)
            .withLabel("com.payverse.testcontainer", "kafka");

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }
}
