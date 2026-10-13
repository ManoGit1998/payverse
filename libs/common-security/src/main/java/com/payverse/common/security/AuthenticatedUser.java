package com.payverse.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The caller behind the current request, resolved from the access token. Declare it as a controller
 * method parameter to receive it.
 *
 * @param subject  JWT {@code sub}: a user id (UUID) for end users, the client id for services
 * @param deviceId bound device, if any
 * @param roles    roles without the {@code ROLE_} prefix
 */
public record AuthenticatedUser(String subject, String deviceId, Set<String> roles) {

    public UUID userId() {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Token subject is not a user id: " + subject, e);
        }
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isService() {
        return hasRole(PayverseRoles.SERVICE);
    }
}
