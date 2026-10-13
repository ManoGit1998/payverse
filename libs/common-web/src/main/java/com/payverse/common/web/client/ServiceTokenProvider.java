package com.payverse.common.web.client;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Obtains and caches a SERVICE access token from auth-service (OAuth2 client_credentials). The token
 * is refreshed {@value #REFRESH_MARGIN_SECONDS}s before it expires.
 */
public class ServiceTokenProvider {

    static final long REFRESH_MARGIN_SECONDS = 30;

    private final RestClient restClient;
    private final ServiceClientProperties properties;
    private final Clock clock;

    private volatile CachedToken cached;

    record CachedToken(String value, Instant expiresAt) {
    }

    public ServiceTokenProvider(RestClient restClient, ServiceClientProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    public String token() {
        CachedToken current = cached;
        if (current != null && clock.instant().isBefore(current.expiresAt().minusSeconds(REFRESH_MARGIN_SECONDS))) {
            return current.value();
        }
        return refresh();
    }

    private synchronized String refresh() {
        CachedToken current = cached;
        if (current != null && clock.instant().isBefore(current.expiresAt().minusSeconds(REFRESH_MARGIN_SECONDS))) {
            return current.value();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        try {
            Map<?, ?> body = restClient.post().uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("access_token") == null) {
                throw new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, "auth-service returned no token");
            }
            long expiresIn = ((Number) body.get("expires_in")).longValue();
            cached = new CachedToken((String) body.get("access_token"), clock.instant().plus(Duration.ofSeconds(expiresIn)));
            return cached.value();
        } catch (RestClientException e) {
            throw new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, "Could not obtain a service token", Map.of(), e);
        }
    }
}
