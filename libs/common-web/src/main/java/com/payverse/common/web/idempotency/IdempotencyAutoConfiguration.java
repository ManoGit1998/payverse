package com.payverse.common.web.idempotency;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Enables {@link IdempotencyService} in services that have both Redis and a database. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
@ConditionalOnClass({StringRedisTemplate.class, JdbcTemplate.class})
@ConditionalOnBean({StringRedisTemplate.class, JdbcTemplate.class, PlatformTransactionManager.class, JsonMapper.class})
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdempotencyService idempotencyService(StringRedisTemplate redis, JdbcTemplate jdbc,
                                          PlatformTransactionManager transactionManager, JsonMapper jsonMapper,
                                          IdempotencyProperties properties) {
        return new IdempotencyService(redis, jdbc, new TransactionTemplate(transactionManager), jsonMapper, properties);
    }
}
