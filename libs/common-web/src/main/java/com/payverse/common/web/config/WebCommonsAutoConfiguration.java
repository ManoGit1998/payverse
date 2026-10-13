package com.payverse.common.web.config;

import com.payverse.common.web.correlation.CorrelationIdClientInterceptor;
import com.payverse.common.web.correlation.CorrelationIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Correlation-ID filter for servlet services and propagation on every Spring-built RestClient. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebCommonsAutoConfiguration {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("correlationIdFilter");
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientCustomizer.class)
    static class RestClientCorrelation {

        @Bean
        RestClientCustomizer correlationIdRestClientCustomizer() {
            return builder -> builder.requestInterceptor(new CorrelationIdClientInterceptor());
        }
    }
}
