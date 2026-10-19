package com.payverse.common.web.provider;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default responses of a provider in STUB mode, read from {@code classpath:provider-stubs/<name>.json}:
 * <pre>{"rules": [{"method": "POST", "path": "/v1/orders", "when": {"body.amount": ".*13$"},
 *                  "status": 200, "headers": {...}, "body": {...}, "times": 1, "onceKey": "{{body:ref_id}}"}]}</pre>
 * The first rule whose method, path regex and {@code when} conditions (regexes on {@code body.<field>},
 * {@code header.<name>} or {@code query.<name>}) match answers. {@code times}/{@code onceKey} make a rule answer only
 * the first N times per key (e.g. one timeout, then success). Strings in {@code body} and {@code headers} may use
 * templates — see {@link StubTemplates}.
 */
public record StubRules(List<Rule> rules) {

    public record Rule(String method, Pattern path, Map<String, Pattern> when, int status, Map<String, String> headers,
                       JsonNode body, Integer times, String onceKey) {
    }

    public static StubRules load(String provider, JsonMapper mapper) {
        ClassPathResource resource = new ClassPathResource("provider-stubs/" + provider + ".json");
        if (!resource.exists()) {
            throw new IllegalStateException("No default responses for provider " + provider + " (provider-stubs/" + provider + ".json)");
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = mapper.readTree(in);
            List<Rule> rules = new ArrayList<>();
            for (JsonNode r : root.path("rules")) {
                Map<String, Pattern> when = new LinkedHashMap<>();
                r.path("when").properties().forEach(e -> when.put(e.getKey(), Pattern.compile(e.getValue().asString())));
                Map<String, String> headers = new LinkedHashMap<>();
                r.path("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
                rules.add(new Rule(r.path("method").asString("GET").toUpperCase(), Pattern.compile(r.path("path").asString(".*")),
                        when, r.path("status").asInt(200), headers, r.get("body"),
                        r.has("times") ? r.get("times").asInt() : null, r.has("onceKey") ? r.get("onceKey").asString() : null));
            }
            return new StubRules(List.copyOf(rules));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read provider-stubs/" + provider + ".json", e);
        }
    }
}
