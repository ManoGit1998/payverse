package com.payverse.common.web.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StubClientHttpRequestFactoryTest {

    private final JsonMapper mapper = new JsonMapper();
    private final RestClient client = RestClient.builder().baseUrl("https://api.example.test")
            .requestFactory(new StubClientHttpRequestFactory("test-provider", StubRules.load("test-provider", mapper), mapper,
                    Clock.fixed(Instant.parse("2026-10-19T06:00:00Z"), ZoneOffset.UTC)))
            .build();

    private JsonNode pay(Map<String, Object> body) {
        return client.post().uri("/v1/pay").body(body).retrieve().body(JsonNode.class);
    }

    @Test
    void defaultResponseIsRenderedFromTheRequest() {
        var entity = client.post().uri("/v1/pay").body(Map.of("amount", 25000, "ref", "r-1", "customer", Map.of("name", "Asha")))
                .retrieve().toEntity(JsonNode.class);

        JsonNode body = entity.getBody();
        assertThat(body.get("id").asString()).matches("pay_[0-9A-F]{14}");
        assertThat(body.get("amount").isNumber()).isTrue();
        assertThat(body.get("amount").asLong()).isEqualTo(25000);
        assertThat(body.get("note").asString()).isEqualTo("for Asha");
        assertThat(body.get("due").asString()).isEqualTo("2026-10-29");
        assertThat(body.get("bill").asLong()).isBetween(100L, 199L).isEqualTo(pay(Map.of("amount", 1, "ref", "r-1")).get("bill").asLong());
        assertThat(entity.getHeaders().getFirst("X-Request-Id")).matches("req_[0-9A-F]{8}");
    }

    @Test
    void conditionsSelectTheRule() {
        assertThatThrownBy(() -> pay(Map.of("amount", 10013, "ref", "r-2")))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("DECLINED");
    }

    @Test
    void timesLimitsARulePerKey() {
        assertThatThrownBy(() -> pay(Map.of("amount", 10014, "ref", "r-3"))).isInstanceOf(HttpServerErrorException.GatewayTimeout.class);
        assertThat(pay(Map.of("amount", 10014, "ref", "r-3")).get("ref").asString()).isEqualTo("r-3");
        assertThatThrownBy(() -> pay(Map.of("amount", 10014, "ref", "r-4"))).isInstanceOf(HttpServerErrorException.GatewayTimeout.class);
    }

    @Test
    void pathGroupsAndQueryParametersAreAvailable() {
        JsonNode body = client.get().uri("/v1/orders/order_ABC123/payments?country=IN").retrieve().body(JsonNode.class);

        assertThat(body.get("order").asString()).isEqualTo("order_ABC123");
        assertThat(body.get("country").asString()).isEqualTo("IN");
    }

    @Test
    void unmatchedRequestsAnswer501() {
        assertThatThrownBy(() -> client.delete().uri("/v1/unknown").retrieve().toBodilessEntity())
                .isInstanceOf(HttpServerErrorException.class)
                .hasMessageContaining("501");
    }

    @Test
    void liveModeRequiresItsCredentials() {
        var props = new ProviderProperties(Map.of("p", new ProviderProperties.Provider("x", ProviderProperties.Mode.LIVE,
                java.net.URI.create("https://api.example.test"), Map.of(), null, null)));
        var clients = new ProviderClients(props, new org.springframework.beans.factory.support.StaticListableBeanFactory(
                Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class), mapper);

        assertThatThrownBy(() -> clients.client("p", java.util.List.of("api-key")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payverse.providers.p.credentials.api-key");
    }
}
