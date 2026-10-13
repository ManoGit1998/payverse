package com.payverse.gateway.config;

import com.payverse.common.security.PayverseClaims;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.security.PayverseRoles;
import com.payverse.common.security.PayverseSecurityProperties;
import com.payverse.gateway.filter.ProblemResponses;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Edge security. The gateway validates tokens (signature via auth-service JWKS, expiry, issuer,
 * audience, typ=access) and enforces coarse route rules; services re-validate and apply fine-grained
 * authorization themselves (defence in depth).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(PayverseSecurityProperties.class)
public class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/otp/**",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/pin/reset/**",
            "/api/v1/pg/webhook/**",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/swagger-ui.html", "/swagger-ui/**", "/webjars/**", "/v3/api-docs/**", "/api-docs/**",
            "/fallback/**"
    };

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, CorsConfigurationSource corsConfigurationSource) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.mode(org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .hsts(hsts -> hsts.includeSubdomains(true).maxAge(Duration.ofDays(365)))
                        .referrerPolicy(ref -> ref.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers("/internal/**", "/api/v1/internal/**").denyAll()
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .pathMatchers("/api/v1/admin/**").hasAnyRole(PayverseRoles.ADMIN, PayverseRoles.SUPPORT)
                        .pathMatchers("/actuator/**").hasRole(PayverseRoles.ADMIN)
                        .anyExchange().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, e) -> {
                            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                            return ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                                    "Authentication required", "A valid bearer token is required");
                        })
                        .accessDeniedHandler((exchange, e) -> ProblemResponses.write(exchange, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "Access denied", "You do not have permission to access this resource")))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter()))
                        .authenticationEntryPoint((exchange, e) -> ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED", "Authentication required", "The bearer token is invalid or expired")))
                .build();
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(PayverseSecurityProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(PayverseJwtValidators.create(properties.issuer(), properties.audience()));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id",
                "X-Txn-Pin", "X-Device-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Idempotent-Replayed", "X-RateLimit-Remaining", "Retry-After"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    static Converter<Jwt, Mono<AbstractAuthenticationToken>> rolesConverter() {
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList(PayverseClaims.ROLES);
            Collection<GrantedAuthority> authorities = roles == null ? List.of()
                    : roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return Mono.just(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
        };
    }
}
