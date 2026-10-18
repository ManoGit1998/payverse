package com.payverse.gateway.config;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Downstream DNS answers are cached for seconds, not for the record's TTL. reactor-netty's resolver honours the TTL,
 * and Docker's embedded DNS hands out long ones: when a service container was recreated (new IP) while the gateway kept
 * running, every call went to the dead IP, failed at once and the circuit breaker answered 503 for minutes.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    static final Duration DNS_CACHE_MAX_TTL = Duration.ofSeconds(10);

    @Bean
    HttpClientCustomizer shortDnsCache() {
        return httpClient -> httpClient.resolver(spec -> spec
                .cacheMaxTimeToLive(DNS_CACHE_MAX_TTL)
                .cacheNegativeTimeToLive(Duration.ofSeconds(1)));
    }
}
