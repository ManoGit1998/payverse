package com.payverse.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * auth-service policy. Secrets (service client secrets) come from env vars.
 *
 * @param issuer          {@code iss} of issued tokens; must match what resource servers expect
 * @param accessTokenTtl  access token lifetime (default 15m)
 * @param refreshTokenTtl refresh token lifetime (default 30d)
 * @param otp             OTP policy
 * @param adminMobiles    mobiles granted ADMIN on login (local dev seeding — no Keycloak)
 * @param supportMobiles  mobiles granted SUPPORT on login
 * @param serviceClients  client-credentials clients for service-to-service tokens (role SERVICE)
 * @param serviceTokenTtl service token lifetime (default 5m)
 */
@ConfigurationProperties("payverse.auth")
public record AuthProperties(String issuer, Duration accessTokenTtl, Duration refreshTokenTtl, Otp otp,
                             List<String> adminMobiles, List<String> supportMobiles,
                             List<ServiceClient> serviceClients, Duration serviceTokenTtl) {

    public AuthProperties {
        issuer = issuer == null ? "http://localhost:8081" : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
        otp = otp == null ? new Otp(null, null, null, null) : otp;
        adminMobiles = adminMobiles == null ? List.of() : List.copyOf(adminMobiles);
        supportMobiles = supportMobiles == null ? List.of() : List.copyOf(supportMobiles);
        serviceClients = serviceClients == null ? List.of() : List.copyOf(serviceClients);
        serviceTokenTtl = serviceTokenTtl == null ? Duration.ofMinutes(5) : serviceTokenTtl;
    }

    /**
     * @param ttl               validity of one OTP (default 5m)
     * @param length            digits (default 6)
     * @param maxRequests       OTP requests allowed per mobile per window (default 3)
     * @param requestWindow     window for {@code maxRequests} (default 10m)
     */
    public record Otp(Duration ttl, Integer length, Integer maxRequests, Duration requestWindow) {

        public Otp {
            ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
            length = length == null ? 6 : length;
            maxRequests = maxRequests == null ? 3 : maxRequests;
            requestWindow = requestWindow == null ? Duration.ofMinutes(10) : requestWindow;
        }
    }

    public record ServiceClient(String id, String secret) {

        @Override
        public String toString() {
            return "ServiceClient[id=" + id + ", secret=****]";
        }
    }
}
