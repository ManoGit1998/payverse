package com.payverse.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

class KafkaListenerThreadsTest {

    @Test
    void listenerContainersRunOnPlatformThreads() throws Exception {
        var container = new ConcurrentMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, String>(java.util.Map.of()), new ContainerProperties("topic"));

        OutboxAutoConfiguration.usePlatformThreads(container, new SimpleAsyncTaskExecutor("kafka-listener-"));

        AsyncTaskExecutor executor = container.getContainerProperties().getListenerTaskExecutor();
        assertThat(executor).isNotNull();
        CompletableFuture<Boolean> virtual = new CompletableFuture<>();
        executor.execute(() -> virtual.complete(Thread.currentThread().isVirtual()));
        assertThat(virtual.get(5, TimeUnit.SECONDS)).isFalse();
    }
}
