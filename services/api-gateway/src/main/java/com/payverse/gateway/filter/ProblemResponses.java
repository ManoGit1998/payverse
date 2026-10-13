package com.payverse.gateway.filter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/** RFC 9457 problem responses written directly by the gateway (401, 403, 503 fallbacks). */
public final class ProblemResponses {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private ProblemResponses() {
    }

    public static ProblemDetail problem(HttpStatus status, String code, String title, String detail, ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://payverse.dev/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        Object correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String title, String detail) {
        byte[] body = MAPPER.writeValueAsBytes(problem(status, code, title, detail, exchange));
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
