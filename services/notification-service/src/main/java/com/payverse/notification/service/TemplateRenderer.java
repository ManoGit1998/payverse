package com.payverse.notification.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, safe template engine: replaces {@code {{name}}} with the value from {@code vars}
 * (unknown names render as empty). Values are stripped of control characters and braces, so event
 * data can never inject new placeholders or break the message.
 */
public final class TemplateRenderer {

    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*}}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, String> vars) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = vars.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(sanitize(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Placeholders are letters/digits only; any other {{…}} is a malformed template. */
    public static boolean isWellFormed(String template) {
        String withoutPlaceholders = PLACEHOLDER.matcher(template).replaceAll("");
        return !withoutPlaceholders.contains("{{") && !withoutPlaceholders.contains("}}");
    }

    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}{}]", "");
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }
}
