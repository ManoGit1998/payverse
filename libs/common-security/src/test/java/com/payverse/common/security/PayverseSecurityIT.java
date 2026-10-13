package com.payverse.common.security;

import com.payverse.common.test.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SecurityTestApplication.class, properties = "payverse.security.public-paths=/api/v1/public/**")
@AutoConfigureMockMvc
class PayverseSecurityIT {

    private static final TestJwts JWTS = TestJwts.INSTANCE;

    @Autowired
    MockMvc mvc;

    @Test
    void missingTokenIsProblem401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validUserTokenResolvesAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, JWTS.bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(userId.toString()))
                .andExpect(jsonPath("$.deviceId").value("test-device"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void currentUserHelperWorks() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(get("/api/v1/current").header(HttpHeaders.AUTHORIZATION, JWTS.bearer(userId)))
                .andExpect(content().string(userId + "|true"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + JWTS.expiredToken(UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        String refresh = JWTS.token(UUID.randomUUID().toString(), List.of("USER"), Map.of("typ", "refresh"), Duration.ofMinutes(5));
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        String token = JWTS.token(UUID.randomUUID().toString(), List.of("USER"), Map.of("aud", "someone-else"), Duration.ofMinutes(5));
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String token = JWTS.token(UUID.randomUUID().toString(), List.of("USER"), Map.of("iss", "https://evil.example"), Duration.ofMinutes(5));
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = JWTS.userToken(UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicPathsAndProbesNeedNoToken() throws Exception {
        mvc.perform(get("/api/v1/public/ping")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/otp/ping")).andExpect(status().isOk());
    }

    @Test
    void internalEndpointsRequireServiceRole() throws Exception {
        mvc.perform(get("/internal/ping").header(HttpHeaders.AUTHORIZATION, JWTS.bearer(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/internal/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + JWTS.token("transaction-service", List.of("SERVICE"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminEndpointsRequireAdminOrSupport() throws Exception {
        mvc.perform(get("/api/v1/admin/ping").header(HttpHeaders.AUTHORIZATION, JWTS.bearer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + JWTS.token(UUID.randomUUID().toString(), List.of("SUPPORT"))))
                .andExpect(status().isOk());
    }

    @Test
    void methodSecurityDenialIsProblem403() throws Exception {
        mvc.perform(get("/api/v1/reports").header(HttpHeaders.AUTHORIZATION, JWTS.bearer(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/reports").header(HttpHeaders.AUTHORIZATION, "Bearer " + JWTS.token(UUID.randomUUID().toString(), List.of("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/v1/public/ping").secure(true))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                .andExpect(header().exists("Strict-Transport-Security"));
    }
}
