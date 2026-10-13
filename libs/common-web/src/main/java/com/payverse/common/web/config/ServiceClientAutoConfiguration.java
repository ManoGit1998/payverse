package com.payverse.common.web.config;

import com.payverse.common.web.audit.AuditLogger;
import com.payverse.common.web.client.ServiceClientProperties;
import com.payverse.common.web.client.ServiceTokenInterceptor;
import com.payverse.common.web.client.ServiceTokenProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/** Service-token support (when {@code payverse.service-client.client-id} is set) and the audit logger. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration"})
@EnableConfigurationProperties(ServiceClientProperties.class)
public class ServiceClientAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnProperty("payverse.service-client.client-id")
    static class ServiceTokens {

        @Bean
        @ConditionalOnMissingBean
        ServiceTokenProvider serviceTokenProvider(RestClient.Builder builder, ServiceClientProperties properties) {
            // a plain client: the token call itself must not carry a service token
            return new ServiceTokenProvider(builder.clone().build(), properties, Clock.systemUTC());
        }

        @Bean
        @ConditionalOnMissingBean
        ServiceTokenInterceptor serviceTokenInterceptor(ServiceTokenProvider provider) {
            return new ServiceTokenInterceptor(provider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    static class Audit {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean({JdbcTemplate.class, JsonMapper.class})
        AuditLogger auditLogger(JdbcTemplate jdbc, JsonMapper jsonMapper) {
            return new AuditLogger(jdbc, jsonMapper);
        }
    }
}
