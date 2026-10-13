package com.payverse.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** OAuth2 token response (snake_case per RFC 6749). */
public record ServiceTokenResponse(@JsonProperty("access_token") String accessToken,
                                   @JsonProperty("token_type") String tokenType,
                                   @JsonProperty("expires_in") long expiresIn) {

    @Override
    public String toString() {
        return "ServiceTokenResponse[****]";
    }
}
