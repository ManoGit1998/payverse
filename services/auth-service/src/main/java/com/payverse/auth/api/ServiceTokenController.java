package com.payverse.auth.api;

import com.payverse.auth.api.AuthDtos.ServiceTokenResponse;
import com.payverse.auth.application.ServiceClientService;
import com.payverse.auth.application.port.AccessTokenIssuer;
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
class ServiceTokenController {

    private final ServiceClientService serviceClients;
    private final Clock clock;

    ServiceTokenController(ServiceClientService serviceClients, Clock clock) {
        this.serviceClients = serviceClients;
        this.clock = clock;
    }

    @Operation(summary = "client_credentials grant")
    @PostMapping(path = "/api/v1/auth/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ServiceTokenResponse token(@RequestParam("grant_type") String grantType,
                               @RequestParam("client_id") String clientId,
                               @RequestParam("client_secret") String clientSecret) {
        if (!"client_credentials".equals(grantType)) {
            throw new PayverseException(CommonErrorCode.MALFORMED_REQUEST, "Only grant_type=client_credentials is supported");
        }
        AccessTokenIssuer.IssuedToken token = serviceClients.issue(clientId, clientSecret);
        return new ServiceTokenResponse(token.value(), "Bearer", AuthDtos.secondsUntil(token.expiresAt(), clock));
    }
}
