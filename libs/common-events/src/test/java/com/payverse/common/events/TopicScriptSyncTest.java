package com.payverse.common.events;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** infra/kafka/create-topics.sh must create every topic in the catalog (auto-create is disabled). */
class TopicScriptSyncTest {

    @Test
    void createTopicsScriptListsEveryTopic() throws IOException {
        String script = Files.readString(Path.of("../../infra/kafka/create-topics.sh"));
        for (EventType type : EventType.values()) {
            assertThat(script).as("create-topics.sh is missing %s", type.topic()).contains("  " + type.topic() + "\n");
        }
    }
}
