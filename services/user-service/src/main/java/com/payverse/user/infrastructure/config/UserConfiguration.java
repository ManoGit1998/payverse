package com.payverse.user.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class UserConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
