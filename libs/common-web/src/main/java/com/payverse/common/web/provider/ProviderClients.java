package com.payverse.common.web.provider;

import com.payverse.common.web.provider.ProviderProperties.Provider;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Builds the {@link RestClient} of an external provider: a real HTTP client (LIVE) or the stub transport (STUB). */
public class ProviderClients {

    private static final Logger log = LoggerFactory.getLogger(ProviderClients.class);

    private final ProviderProperties properties;
    private final ObjectProvider<RestClient.Builder> builders;
    private final JsonMapper jsonMapper;

    public ProviderClients(ProviderProperties properties, ObjectProvider<RestClient.Builder> builders, JsonMapper jsonMapper) {
        this.properties = properties;
        this.builders = builders;
        this.jsonMapper = jsonMapper;
    }

    public Provider settings(String name) {
        Provider provider = properties.providers().get(name);
        if (provider == null) {
            throw new IllegalStateException("Missing configuration payverse.providers." + name);
        }
        return provider;
    }

    /**
     * @param requiredCredentials credentials that must be set before LIVE mode may start
     */
    public RestClient client(String name, List<String> requiredCredentials) {
        Provider provider = settings(name);
        if (provider.baseUrl() == null) {
            throw new IllegalStateException("payverse.providers." + name + ".base-url is required");
        }
        // the Boot builder carries observation (tracing/metrics); services without one get a plain builder
        RestClient.Builder builder = builders.getIfAvailable(RestClient::builder).baseUrl(provider.baseUrl().toString());
        if (provider.live()) {
            List<String> missing = requiredCredentials.stream()
                    .filter(key -> provider.credentials().getOrDefault(key, "").isBlank()).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Provider " + name + " is LIVE but credentials are missing: "
                        + missing.stream().map(k -> "payverse.providers." + name + ".credentials." + k).toList());
            }
            // HTTP/1.1: the JDK client would otherwise try an h2c upgrade on plain-http sandboxes, which many servers reset
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(provider.connectTimeout()).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(provider.readTimeout());
            log.info("Provider {} ({}) is LIVE: {}", name, provider.adapter(), provider.baseUrl());
            return builder.requestFactory(factory).build();
        }
        log.warn("Provider {} ({}) is in STUB mode: requests are built as for {} but answered with default responses "
                + "from provider-stubs/{}.json. Set payverse.providers.{}.mode=LIVE with real credentials to go live.",
                name, provider.adapter(), provider.baseUrl(), name, name);
        return builder.requestFactory(new StubClientHttpRequestFactory(name, StubRules.load(name, jsonMapper), jsonMapper)).build();
    }

    public Map<String, Provider> all() {
        return properties.providers();
    }
}
