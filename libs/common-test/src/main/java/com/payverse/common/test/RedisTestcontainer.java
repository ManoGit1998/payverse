package com.payverse.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Reusable Redis container wired through {@code @ServiceConnection}. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainer {

    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8-alpine"))
            .withExposedPorts(6379)
            .withReuse(true)
            .withLabel("com.payverse.testcontainer", "redis");

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return REDIS;
    }
}
