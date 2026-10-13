package com.payverse.auth.service;

import com.payverse.auth.config.AuthProperties;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.security.JwtTokenProvider;
import com.payverse.auth.security.SecureTokens;
import com.payverse.common.error.PayverseException;
import com.payverse.common.security.PayverseRoles;
import org.springframework.stereotype.Service;

import java.util.Set;

/** OAuth2 client-credentials grant for service-to-service calls (tokens carry role SERVICE). */
@Service
public class ServiceClientService {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthProperties properties;

    public ServiceClientService(JwtTokenProvider jwtTokenProvider, AuthProperties properties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.properties = properties;
    }

    public JwtTokenProvider.IssuedToken issue(String clientId, String clientSecret) {
        boolean valid = properties.serviceClients().stream()
                .anyMatch(client -> client.id().equals(clientId) && client.secret() != null && !client.secret().isBlank()
                        && SecureTokens.constantTimeEquals(client.secret(), clientSecret));
        if (!valid) {
            throw new PayverseException(AuthErrorCode.AUTH_INVALID_CLIENT, null);
        }
        return jwtTokenProvider.issue(clientId, Set.of(PayverseRoles.SERVICE), null, properties.serviceTokenTtl());
    }
}
