package com.payverse.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Access to the authenticated caller outside controllers (application services, auditing). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AuthenticatedUser> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return Optional.of(from(jwtAuth.getToken()));
        }
        return Optional.empty();
    }

    public static AuthenticatedUser require() {
        return get().orElseThrow(() -> new IllegalStateException("No authenticated user in context"));
    }

    /** Raw bearer token of the current request, for token relay to downstream services. */
    public static Optional<String> bearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken jwtAuth
                ? Optional.of(jwtAuth.getToken().getTokenValue()) : Optional.empty();
    }

    static AuthenticatedUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(PayverseClaims.ROLES);
        Set<String> roleSet = roles == null ? Set.of() : Set.copyOf(new HashSet<>(roles));
        return new AuthenticatedUser(jwt.getSubject(), jwt.getClaimAsString(PayverseClaims.DEVICE_ID), roleSet);
    }
}
