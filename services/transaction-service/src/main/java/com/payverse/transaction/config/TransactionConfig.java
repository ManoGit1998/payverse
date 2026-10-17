package com.payverse.transaction.config;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.client.RemoteProblemHandler;
import com.payverse.common.web.client.ServiceTokenInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TransactionProperties.class)
@EnableScheduling
public class TransactionConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient walletRestClient(RestClient.Builder builder, ServiceTokenInterceptor serviceToken, TransactionProperties properties) {
        return serviceClient(builder, serviceToken, properties.walletServiceUrl(), "wallet-service");
    }

    @Bean
    RestClient authRestClient(RestClient.Builder builder, ServiceTokenInterceptor serviceToken, TransactionProperties properties) {
        return serviceClient(builder, serviceToken, properties.authServiceUrl(), "auth-service");
    }

    @Bean
    RestClient paymentRestClient(RestClient.Builder builder, ServiceTokenInterceptor serviceToken, TransactionProperties properties) {
        return serviceClient(builder, serviceToken, properties.paymentServiceUrl(), "payment-service");
    }

    @Bean
    RestClient fraudRestClient(RestClient.Builder builder, ServiceTokenInterceptor serviceToken, TransactionProperties properties) {
        return serviceClient(builder, serviceToken, properties.fraudServiceUrl(), "fraud-risk-service");
    }

    @Bean
    RestClient userRestClient(RestClient.Builder builder, ServiceTokenInterceptor serviceToken, TransactionProperties properties) {
        return serviceClient(builder, serviceToken, properties.userServiceUrl(), "user-service");
    }

    /**
     * Circuit breakers for downstream calls: 3s time limit; only infrastructure failures (5xx,
     * timeouts, connection errors) count — business 4xx answers like AUTH_PIN_INCORRECT do not.
     */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> circuitBreakerDefaults() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build())
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .recordException(e -> !(e instanceof PayverseException p) || p.errorCode().httpStatus() >= 500)
                        .build())
                .build());
    }

    private static RestClient serviceClient(RestClient.Builder builder, ServiceTokenInterceptor serviceToken, String baseUrl, String name) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        RestClient.Builder configured = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(serviceToken);
        return RemoteProblemHandler.applyTo(configured, name).build();
    }
}
