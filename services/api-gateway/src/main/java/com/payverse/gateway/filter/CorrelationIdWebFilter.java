package com.payverse.gateway.filter;

import com.payverse.common.web.correlation.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Accepts a well-formed {@code X-Correlation-Id} from the client or generates one, forwards it to the
 * downstream service and echoes it on the response. Runs before Spring Security so rejected requests
 * (401/403/429) carry the id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.sanitizeOrGenerate(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        exchange.getAttributes().put(ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
        ServerWebExchange mutated = exchange.mutate()
                .request(exchange.getRequest().mutate().headers(h -> h.set(CorrelationId.HEADER, correlationId)).build())
                .build();
        return chain.filter(mutated);
    }
}
