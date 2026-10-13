package com.payverse.common.web.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Connects the {@code OTEL} Logback appender (declared in {@code logback-spring.xml}) to the
 * OpenTelemetry SDK so logs are exported via OTLP to grafana/otel-lgtm alongside traces and metrics.
 */
@AutoConfiguration(afterName = "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass({OpenTelemetry.class, OpenTelemetryAppender.class})
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    InitializingBean openTelemetryLogbackInstaller(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
