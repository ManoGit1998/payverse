package com.payverse.gateway.controller;

import com.payverse.gateway.filter.ProblemResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** Circuit-breaker fallback: downstream unavailable → 503 problem instead of a hanging request. */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<ProblemDetail> fallback(@PathVariable String service, ServerWebExchange exchange) {
        ProblemDetail problem = ProblemResponses.problem(HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE",
                "A downstream service is unavailable", service + " is temporarily unavailable, please retry", exchange);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(problem);
    }
}
