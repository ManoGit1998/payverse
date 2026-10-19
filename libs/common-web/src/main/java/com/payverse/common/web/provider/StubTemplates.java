package com.payverse.common.web.provider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Template functions for default responses: {@code {{uuid}}}, {@code {{hex:N}}}, {@code {{digits:N}}},
 * {@code {{epoch}}}, {@code {{date:+D}}} (IST date D days from today), {@code {{body:a.b}}} (a field of the request
 * JSON, keeping its type when it is the whole value), {@code {{query:name}}}, {@code {{path:N}}} (regex group of the
 * rule's path) and {@code {{hash:<expr>:min:max}}} (a stable number in [min, max) derived from another template).
 */
final class StubTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)}}");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    record Context(JsonNode body, Map<String, String> query, Matcher path, Clock clock) {
    }

    private StubTemplates() {
    }

    static JsonNode render(JsonNode template, Context ctx) {
        if (template == null) {
            return null;
        }
        if (template.isString()) {
            String text = template.asString();
            Matcher whole = PLACEHOLDER.matcher(text);
            if (whole.matches()) {
                JsonNode value = evaluate(whole.group(1), ctx);
                return value == null ? JsonNodeFactory.instance.nullNode() : value;
            }
            return JsonNodeFactory.instance.stringNode(renderText(text, ctx));
        }
        if (template.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            template.properties().forEach(e -> out.set(e.getKey(), render(e.getValue(), ctx)));
            return out;
        }
        if (template.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            template.forEach(item -> out.add(render(item, ctx)));
            return out;
        }
        return template;
    }

    static String renderText(String text, Context ctx) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode value = evaluate(m.group(1), ctx);
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null || value.isNull() ? "" : value.asString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static JsonNode evaluate(String expression, Context ctx) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        String[] parts = expression.trim().split(":", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        return switch (parts[0]) {
            case "uuid" -> f.stringNode(UUID.randomUUID().toString());
            case "hex" -> f.stringNode(UUID.randomUUID().toString().replace("-", "").substring(0, Integer.parseInt(arg)).toUpperCase());
            case "digits" -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Integer.parseInt(arg); i++) {
                    sb.append(ThreadLocalRandom.current().nextInt(i == 0 ? 1 : 0, 10));
                }
                yield f.stringNode(sb.toString());
            }
            case "epoch" -> f.numberNode(ctx.clock().instant().getEpochSecond());
            case "date" -> f.stringNode(LocalDate.now(ctx.clock().withZone(IST)).plusDays(Long.parseLong(arg.replace("+", ""))).toString());
            case "body" -> {
                JsonNode node = field(ctx.body(), arg);
                yield node == null || node.isMissingNode() ? null : node;
            }
            case "query" -> ctx.query().containsKey(arg) ? f.stringNode(ctx.query().get(arg)) : null;
            case "path" -> f.stringNode(ctx.path().group(Integer.parseInt(arg)));
            case "hash" -> {
                int last = arg.lastIndexOf(':');
                int prev = arg.lastIndexOf(':', last - 1);
                long min = Long.parseLong(arg.substring(prev + 1, last));
                long max = Long.parseLong(arg.substring(last + 1));
                String seed = renderText("{{" + arg.substring(0, prev) + "}}", ctx) + "|" + LocalDate.now(ctx.clock().withZone(IST)).withDayOfMonth(1);
                yield f.numberNode(min + new BigInteger(1, sha256(seed)).mod(BigInteger.valueOf(max - min)).longValue());
            }
            default -> throw new IllegalArgumentException("Unknown stub template function: " + parts[0]);
        };
    }

    /** {@code a.b.0.c} → nested field / array index. */
    static JsonNode field(JsonNode root, String dotted) {
        JsonNode node = root;
        for (String key : dotted.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = key.chars().allMatch(Character::isDigit) && node.isArray() ? node.get(Integer.parseInt(key)) : node.get(key);
        }
        return node;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
