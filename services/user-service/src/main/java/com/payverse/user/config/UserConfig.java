package com.payverse.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class UserConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
