package com.payverse.auth.controller;

import com.payverse.auth.dto.ServiceTokenResponse;
import com.payverse.auth.dto.TokenResponse;
import com.payverse.auth.security.JwtTokenProvider;
import com.payverse.auth.service.ServiceClientService;
import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/** OAuth2 client-credentials token endpoint for services (role SERVICE, used on /internal/** APIs). */
@RestController
@Tag(name = "Service tokens")
public class ServiceTokenController {

    private final ServiceClientService serviceClients;
    private final Clock clock;

    public ServiceTokenController(ServiceClientService serviceClients, Clock clock) {
        this.serviceClients = serviceClients;
        this.clock = clock;
    }

    @Operation(summary = "client_credentials grant")
    @PostMapping(path = "/api/v1/auth/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ServiceTokenResponse token(@RequestParam("grant_type") String grantType,
                                      @RequestParam("client_id") String clientId,
                                      @RequestParam("client_secret") String clientSecret) {
        if (!"client_credentials".equals(grantType)) {
            throw new PayverseException(CommonErrorCode.MALFORMED_REQUEST, "Only grant_type=client_credentials is supported");
        }
        JwtTokenProvider.IssuedToken token = serviceClients.issue(clientId, clientSecret);
        return new ServiceTokenResponse(token.value(), "Bearer", TokenResponse.secondsUntil(token.expiresAt(), clock));
    }
}
