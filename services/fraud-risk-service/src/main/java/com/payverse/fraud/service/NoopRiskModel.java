package com.payverse.fraud.service;

import com.payverse.fraud.model.RiskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.OptionalInt;

/** MOCK model: no ML yet, rules decide alone. Register another {@link RiskModel} bean to replace it. */
@Configuration(proxyBeanMethods = false)
public class NoopRiskModel {

    private static final Logger log = LoggerFactory.getLogger(NoopRiskModel.class);

    @Bean
    @ConditionalOnMissingBean(RiskModel.class)
    RiskModel rulesOnlyModel() {
        log.info("No ML risk model configured (MOCK NoopRiskModel): rules-only scoring.");
        return new RiskModel() {
            @Override
            public OptionalInt score(RiskContext context) {
                return OptionalInt.empty();
            }

            @Override
            public String version() {
                return "rules-v1";
            }
        };
    }
}
