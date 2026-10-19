package com.payverse.common.web.provider;

import com.payverse.common.web.provider.StubRules.Rule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * STUB transport: answers a provider adapter's real HTTP requests with the default responses of {@link StubRules},
 * without any network call. The adapter's serialisation, headers and response parsing run exactly as in LIVE mode.
 */
public class StubClientHttpRequestFactory implements ClientHttpRequestFactory {

    private static final Logger log = LoggerFactory.getLogger(StubClientHttpRequestFactory.class);

    private final String provider;
    private final StubRules rules;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Map<String, AtomicInteger> usage = new ConcurrentHashMap<>();

    public StubClientHttpRequestFactory(String provider, StubRules rules, JsonMapper mapper) {
        this(provider, rules, mapper, Clock.systemUTC());
    }

    StubClientHttpRequestFactory(String provider, StubRules rules, JsonMapper mapper, Clock clock) {
        this.provider = provider;
        this.rules = rules;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new StubRequest(uri, method);
    }

    private ClientHttpResponse answer(URI uri, HttpMethod method, HttpHeaders requestHeaders, byte[] requestBody) {
        JsonNode body = parse(requestBody);
        Map<String, String> query = query(uri);
        String path = uri.getRawPath();
        for (int i = 0; i < rules.rules().size(); i++) {
            Rule rule = rules.rules().get(i);
            Matcher pathMatch = rule.path().matcher(path);
            if (!rule.method().equals(method.name()) || !pathMatch.matches() || !conditionsHold(rule, body, query, requestHeaders)) {
                continue;
            }
            StubTemplates.Context ctx = new StubTemplates.Context(body, query, pathMatch, clock);
            if (rule.times() != null) {
                String key = i + "|" + (rule.onceKey() == null ? "" : StubTemplates.renderText(rule.onceKey(), ctx));
                if (usage.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() > rule.times()) {
                    continue;
                }
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            rule.headers().forEach((k, v) -> headers.set(k, StubTemplates.renderText(v, ctx)));
            JsonNode rendered = StubTemplates.render(rule.body(), ctx);
            byte[] bytes = rendered == null ? new byte[0] : mapper.writeValueAsBytes(rendered);
            log.info("[STUB {}] {} {} -> {} (default response)", provider, method, path, rule.status());
            return new StubResponse(HttpStatusCode.valueOf(rule.status()), headers, bytes);
        }
        log.warn("[STUB {}] {} {} -> 501: no default response matches", provider, method, path);
        return new StubResponse(HttpStatusCode.valueOf(501), new HttpHeaders(),
                ("{\"error\":\"no stub rule for " + method + " " + path + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private boolean conditionsHold(Rule rule, JsonNode body, Map<String, String> query, HttpHeaders headers) {
        for (var condition : rule.when().entrySet()) {
            String key = condition.getKey();
            String actual;
            if (key.startsWith("body.")) {
                JsonNode node = StubTemplates.field(body, key.substring(5));
                actual = node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
            } else if (key.startsWith("header.")) {
                actual = headers.getFirst(key.substring(7));
            } else if (key.startsWith("query.")) {
                actual = query.get(key.substring(6));
            } else {
                throw new IllegalStateException("Unknown stub condition " + key + " (use body., header. or query.)");
            }
            if (actual == null || !condition.getValue().matcher(actual).matches()) {
                return false;
            }
        }
        return true;
    }

    private JsonNode parse(byte[] body) {
        if (body.length == 0) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            // form-encoded or binary payloads: expose form fields as body.<name>
            var node = JsonNodeFactory.instance.objectNode();
            for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    node.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            return node;
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                out.put(key, eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private final class StubRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final HttpHeaders headers = new HttpHeaders();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        StubRequest(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public ClientHttpResponse execute() {
            return answer(uri, method, headers, body.toByteArray());
        }

        @Override
        public OutputStream getBody() {
            return body;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }
    }

    private record StubResponse(HttpStatusCode statusCode, HttpHeaders headers, byte[] bytes) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return statusCode;
        }

        @Override
        public String getStatusText() {
            return "STUB";
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {
        }
    }
}
