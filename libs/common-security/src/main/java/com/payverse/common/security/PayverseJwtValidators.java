package com.payverse.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;

/**
 * Token validation shared by every resource server: signature (by the decoder), expiry/not-before,
 * issuer, audience, and {@code typ=access} so refresh tokens can never be used as access tokens.
 */
public final class PayverseJwtValidators {

    private PayverseJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> create(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(audience));
        OAuth2TokenValidator<Jwt> accessTokenOnly = jwt -> PayverseClaims.TOKEN_TYPE_ACCESS
                .equals(jwt.getClaimAsString(PayverseClaims.TOKEN_TYPE))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, accessTokenOnly);
    }

    public static NimbusJwtDecoder configure(NimbusJwtDecoder decoder, String issuer, String audience) {
        decoder.setJwtValidator(create(issuer, audience));
        return decoder;
    }
}
