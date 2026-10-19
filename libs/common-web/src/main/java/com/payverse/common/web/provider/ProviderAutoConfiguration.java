package com.payverse.common.web.provider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** {@link ProviderClients} for every service that integrates an external provider. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration"})
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProviderClients providerClients(ProviderProperties properties, ObjectProvider<RestClient.Builder> builders,
                                    ObjectProvider<JsonMapper> jsonMapper) {
        return new ProviderClients(properties, builders, jsonMapper.getIfAvailable(JsonMapper::new));
    }
}
