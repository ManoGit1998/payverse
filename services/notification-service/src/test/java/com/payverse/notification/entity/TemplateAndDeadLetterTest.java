package com.payverse.notification.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateAndDeadLetterTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void templateCanBeUpdatedAndDeactivated() {
        Template t = Template.create("X", Channel.SMS, null, "Hi {{name}}", NOW);
        assertThat(t.isActive()).isTrue();
        t.update("Subj", "Bye", false, NOW.plusSeconds(1));
        assertThat(t.getSubject()).isEqualTo("Subj");
        assertThat(t.getBody()).isEqualTo("Bye");
        assertThat(t.isActive()).isFalse();
        assertThat(t.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(t.getCode()).isEqualTo("X");
        assertThat(t.getChannel()).isEqualTo(Channel.SMS);
    }

    @Test
    void deadLetterDerivesOriginalTopicAndTracksReplay() {
        DeadLetterEvent e = DeadLetterEvent.capture("payverse.a.b.v1.dlt", 1, 42, "k", "{}", "x".repeat(2000), NOW);
        assertThat(e.getOriginalTopic()).isEqualTo("payverse.a.b.v1");
        assertThat(e.getErrorMessage()).hasSize(1000);
        assertThat(e.isReplayed()).isFalse();
        e.markReplayed(NOW);
        assertThat(e.isReplayed()).isTrue();
        assertThat(e.getReplayedAt()).isEqualTo(NOW);

        DeadLetterEvent odd = DeadLetterEvent.capture("weird", 0, 0, null, "", null, NOW);
        assertThat(odd.getOriginalTopic()).isEqualTo("weird");
        assertThat(odd.getErrorMessage()).isNull();
    }
}
