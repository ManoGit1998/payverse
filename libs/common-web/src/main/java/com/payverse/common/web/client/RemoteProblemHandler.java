package com.payverse.common.web.client;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns error responses from other PayVerse services into {@link PayverseException}s:
 * <ul>
 *   <li>4xx with an RFC 9457 body → same {@code code}/status/detail (e.g. {@code AUTH_PIN_INCORRECT} with
 *       {@code remainingAttempts}) so the original meaning reaches our client;</li>
 *   <li>5xx or unparseable bodies → {@code DOWNSTREAM_UNAVAILABLE} (503).</li>
 * </ul>
 */
public final class RemoteProblemHandler {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> STANDARD_FIELDS = Set.of("type", "title", "status", "detail", "instance", "code",
            "timestamp", "correlationId", "errors");

    private RemoteProblemHandler() {
    }

    public static RestClient.Builder applyTo(RestClient.Builder builder, String serviceName) {
        return builder.defaultStatusHandler(status -> status.isError(), (request, response) -> {
            throw toException(serviceName, response);
        });
    }

    static PayverseException toException(String serviceName, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status >= 500) {
            return new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, serviceName + " is unavailable");
        }
        try {
            Map<?, ?> body = MAPPER.readValue(response.getBody(), Map.class);
            Object code = body.get("code");
            if (code == null) {
                return new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, serviceName + " returned " + status);
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            body.forEach((k, v) -> {
                if (!STANDARD_FIELDS.contains(String.valueOf(k)) && v != null) {
                    extra.put(String.valueOf(k), v);
                }
            });
            Object title = body.get("title");
            Object detail = body.get("detail");
            return new PayverseException(new RemoteErrorCode(code.toString(), status, title == null ? code.toString() : title.toString()),
                    detail == null ? null : detail.toString(), extra);
        } catch (RuntimeException e) {
            return new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, serviceName + " returned " + status);
        }
    }
}
