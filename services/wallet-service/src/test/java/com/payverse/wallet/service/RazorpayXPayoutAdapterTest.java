package com.payverse.wallet.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties;
import com.payverse.common.web.provider.ProviderProperties.Mode;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RazorpayXPayoutAdapterTest {

    static final WireMockServer LIVE = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void start() {
        LIVE.start();
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    static RazorpayXPayoutAdapter adapter(Mode mode, String baseUrl, Map<String, String> credentials) {
        var props = new ProviderProperties(Map.of("payout", new ProviderProperties.Provider("razorpayx", mode, URI.create(baseUrl), credentials, null, null)));
        return new RazorpayXPayoutAdapter(new ProviderClients(props,
                new StaticListableBeanFactory(Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class), new JsonMapper()));
    }

    @Test
    void stubAcceptsThePayout() {
        assertThat(adapter(Mode.STUB, "https://api.razorpay.com", Map.of())
                .initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr("200.00"), "withdraw:" + UUID.randomUUID()))
                .matches("pout_[0-9A-F]{14}");
    }

    @Test
    void stubRejectsAmountsEndingIn14() {
        assertThatThrownBy(() -> adapter(Mode.STUB, "https://api.razorpay.com", Map.of())
                .initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr("100.14"), "k-" + UUID.randomUUID()))
                .isInstanceOf(PayverseException.class)
                .hasMessageContaining("Beneficiary bank rejected the payout");
    }

    @Test
    void liveSendsAnIdempotentImpsPayout() {
        UUID bank = UUID.randomUUID();
        String key = "withdraw:" + UUID.randomUUID();
        LIVE.stubFor(post(urlEqualTo("/v1/payouts")).withHeader("X-Payout-Idempotency", equalTo(key))
                .willReturn(okJson("{\"id\":\"pout_LIVE1\",\"entity\":\"payout\",\"status\":\"processing\"}")));

        String reference = adapter(Mode.LIVE, LIVE.baseUrl(), Map.of("key-id", "rzp_test", "key-secret", "s", "account-number", "2323230000000001"))
                .initiate(UUID.randomUUID(), bank, Money.inr("200.00"), key);

        assertThat(reference).isEqualTo("pout_LIVE1");
        LIVE.verify(postRequestedFor(urlEqualTo("/v1/payouts")).withRequestBody(equalToJson(
                "{\"account_number\":\"2323230000000001\",\"fund_account_id\":\"" + RazorpayXPayoutAdapter.fundAccountId(bank) + "\","
                        + "\"amount\":20000,\"currency\":\"INR\",\"mode\":\"IMPS\",\"purpose\":\"payout\",\"queue_if_low_balance\":true}", true, true)));
    }

    @Test
    void liveValidationErrorFailsTheWithdrawal() {
        LIVE.stubFor(post(urlEqualTo("/v1/payouts")).withHeader("X-Payout-Idempotency", equalTo("bad"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The fund account id provided does not exist\"}}")));

        assertThatThrownBy(() -> adapter(Mode.LIVE, LIVE.baseUrl(), Map.of("key-id", "k", "key-secret", "s", "account-number", "1"))
                .initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr("5.00"), "bad"))
                .isInstanceOf(PayverseException.class)
                .hasMessageContaining("fund account id provided does not exist");
    }
}
