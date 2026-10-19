package com.payverse.common.web.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External providers ({@code payverse.providers.<name>}): which adapter implements a port and how it reaches the
 * provider. In {@link Mode#STUB} the adapter runs its real request/response code but the HTTP transport returns the
 * provider's canned default responses ({@code classpath:provider-stubs/<name>.json}); in {@link Mode#LIVE} it calls
 * {@code base-url} with the configured credentials. Going live is configuration only: mode, base URL and secrets.
 */
@ConfigurationProperties("payverse")
public record ProviderProperties(Map<String, Provider> providers) {

    public enum Mode { STUB, LIVE }

    public ProviderProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    /**
     * @param adapter      which implementation of the port is active ({@code mock} keeps the old simulators)
     * @param mode         STUB (default responses, no network) or LIVE
     * @param baseUrl      the provider's API root
     * @param credentials  provider-specific secrets and ids (keys, tokens, account numbers, template ids)
     */
    public record Provider(String adapter, Mode mode, URI baseUrl, Map<String, String> credentials,
                           Duration connectTimeout, Duration readTimeout) {

        public Provider {
            mode = mode == null ? Mode.STUB : mode;
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        }

        public boolean live() {
            return mode == Mode.LIVE;
        }

        /** A credential; blank in STUB mode is fine (a placeholder is sent), in LIVE mode it fails fast at startup. */
        public String credential(String key) {
            String value = credentials.get(key);
            return value == null || value.isBlank() ? "stub-" + key : value;
        }
    }
}
