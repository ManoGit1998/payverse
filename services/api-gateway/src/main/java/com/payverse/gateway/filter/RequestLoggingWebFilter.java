package com.payverse.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * One structured access-log line per request — including requests rejected by security or the rate
 * limiter. Never logs bodies, tokens or query strings. The user id is filled in by
 * {@link PrincipalCaptureGlobalFilter} for authenticated, routed requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger("payverse.gateway.access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            String method = exchange.getRequest().getMethod().name();
            String path = exchange.getRequest().getPath().value();
            String correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);
            String userId = exchange.getAttributeOrDefault(PrincipalCaptureGlobalFilter.ATTRIBUTE, "anonymous");
            log.atInfo()
                    .addKeyValue("correlationId", correlationId)
                    .addKeyValue("method", method)
                    .addKeyValue("path", path)
                    .addKeyValue("route", route != null ? route.getId() : "none")
                    .addKeyValue("status", status != null ? status.value() : 0)
                    .addKeyValue("userId", userId)
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .log("{} {} -> {}", method, path, status != null ? status.value() : "-");
        });
    }
}
