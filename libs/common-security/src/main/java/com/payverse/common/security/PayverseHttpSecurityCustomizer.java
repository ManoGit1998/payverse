package com.payverse.common.security;

import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Adds service-specific authorization rules, applied <b>before</b> the default
 * {@code /internal/**}, admin and catch-all rules.
 */
@FunctionalInterface
public interface PayverseHttpSecurityCustomizer {

    void customize(AuthorizeHttpRequestsConfigurer<?>.AuthorizationManagerRequestMatcherRegistry registry);
}
