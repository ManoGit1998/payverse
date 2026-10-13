package com.payverse.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Default security for every servlet PayVerse service (OAuth2 resource server, stateless):
 * <ul>
 *   <li>public: health/info probes, OpenAPI/Swagger, plus {@code payverse.security.public-paths}</li>
 *   <li>{@code /internal/**}: {@code SERVICE} role (service-to-service tokens only)</li>
 *   <li>{@code /api/v1/admin/**}: {@code ADMIN} or {@code SUPPORT}</li>
 *   <li>everything else: any authenticated access token</li>
 * </ul>
 * A service can replace the chain entirely by declaring its own {@link SecurityFilterChain} bean, or
 * add rules through {@link PayverseHttpSecurityCustomizer} beans.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(PayverseSecurityProperties.class)
@EnableWebSecurity
@EnableMethodSecurity
public class PayverseSecurityAutoConfiguration {

    static final String[] ALWAYS_PUBLIC = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/error"
    };

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(PayverseSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        return PayverseJwtValidators.configure(decoder, properties.issuer(), properties.audience());
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain payverseSecurityFilterChain(HttpSecurity http, PayverseSecurityProperties properties,
                                                    List<PayverseHttpSecurityCustomizer> customizers) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'none'")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(ProblemJsonSecurityHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(ProblemJsonSecurityHandlers.accessDeniedHandler()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new RolesJwtAuthenticationConverter()))
                        .authenticationEntryPoint(ProblemJsonSecurityHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(ProblemJsonSecurityHandlers.accessDeniedHandler()));

        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(ALWAYS_PUBLIC).permitAll();
            if (!properties.publicPaths().isEmpty()) {
                auth.requestMatchers(properties.publicPaths().toArray(String[]::new)).permitAll();
            }
            customizers.forEach(c -> c.customize(auth));
            auth.requestMatchers("/internal/**").hasRole(PayverseRoles.SERVICE)
                    .requestMatchers("/api/v1/admin/**").hasAnyRole(PayverseRoles.ADMIN, PayverseRoles.SUPPORT)
                    .anyRequest().authenticated();
        });
        return http.build();
    }

    @Bean
    SecurityExceptionAdvice securityExceptionAdvice() {
        return new SecurityExceptionAdvice();
    }

    @Bean
    WebMvcConfigurer authenticatedUserResolverConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new AuthenticatedUserArgumentResolver());
            }
        };
    }
}
