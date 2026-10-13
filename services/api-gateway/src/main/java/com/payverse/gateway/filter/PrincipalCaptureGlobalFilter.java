package com.payverse.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Records the authenticated subject in the exchange attributes for the access log. */
@Component
public class PrincipalCaptureGlobalFilter implements GlobalFilter, Ordered {

    public static final String ATTRIBUTE = PrincipalCaptureGlobalFilter.class.getName() + ".userId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .doOnNext(principal -> exchange.getAttributes().put(ATTRIBUTE, principal.getName()))
                .then(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
