package com.payverse.auth.application;

import com.payverse.auth.application.port.AccessTokenIssuer;
import com.payverse.auth.domain.AuthErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.security.PayverseRoles;
import org.springframework.stereotype.Service;

import java.util.Set;

/** OAuth2 client-credentials grant for service-to-service calls (tokens carry role SERVICE). */
@Service
public class ServiceClientService {

    private final AccessTokenIssuer issuer;
    private final AuthProperties properties;

    public ServiceClientService(AccessTokenIssuer issuer, AuthProperties properties) {
        this.issuer = issuer;
        this.properties = properties;
    }

    public AccessTokenIssuer.IssuedToken issue(String clientId, String clientSecret) {
        boolean valid = properties.serviceClients().stream()
                .anyMatch(client -> client.id().equals(clientId) && client.secret() != null && !client.secret().isBlank()
                        && SecureTokens.constantTimeEquals(client.secret(), clientSecret));
        if (!valid) {
            throw new PayverseException(AuthErrorCode.AUTH_INVALID_CLIENT, null);
        }
        return issuer.issue(clientId, Set.of(PayverseRoles.SERVICE), null, properties.serviceTokenTtl());
    }
}
