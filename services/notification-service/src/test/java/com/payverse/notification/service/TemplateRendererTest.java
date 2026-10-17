package com.payverse.notification.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    @Test
    void replacesPlaceholdersAndBlanksUnknownOnes() {
        assertThat(TemplateRenderer.render("INR {{amount}} from {{ who }}{{missing}}.", Map.of("amount", "10.00", "who", "Asha")))
                .isEqualTo("INR 10.00 from Asha.");
        assertThat(TemplateRenderer.render(null, Map.of())).isNull();
    }

    @Test
    void valuesCannotInjectPlaceholdersOrControlCharacters() {
        String out = TemplateRenderer.render("Note: {{note}}", Map.of("note", "{{amount}}\n$1\\x"));
        assertThat(out).isEqualTo("Note: amount$1\\x");
        assertThat(TemplateRenderer.sanitize("a".repeat(300))).hasSize(200);
        assertThat(TemplateRenderer.sanitize(null)).isEmpty();
    }

    @Test
    void detectsMalformedTemplates() {
        assertThat(TemplateRenderer.isWellFormed("Hi {{name}}")).isTrue();
        assertThat(TemplateRenderer.isWellFormed("Hi {{na-me}}")).isFalse();
        assertThat(TemplateRenderer.isWellFormed("Hi {{name")).isFalse();
        assertThat(TemplateRenderer.isWellFormed("Hi name}}")).isFalse();
    }
}
