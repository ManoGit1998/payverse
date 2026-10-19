package com.payverse.bill.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.payverse.bill.entity.Biller;
import com.payverse.common.money.Money;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties;
import com.payverse.common.web.provider.ProviderProperties.Mode;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class BbpsBillerAdapterTest {

    static final WireMockServer LIVE = new WireMockServer(wireMockConfig().dynamicPort());
    static final Map<String, String> CREDENTIALS = Map.of("api-key", "bbps-key", "agent-id", "AG01");

    @BeforeAll
    static void start() {
        LIVE.start();
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    static BbpsBillerAdapter adapter(Mode mode, String baseUrl, Map<String, String> credentials) {
        var props = new ProviderProperties(Map.of("biller", new ProviderProperties.Provider("bbps", mode, URI.create(baseUrl), credentials, null, null)));
        return new BbpsBillerAdapter(new ProviderClients(props,
                new StaticListableBeanFactory(Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class), new JsonMapper()));
    }

    static Biller biller() {
        Biller biller = mock(Biller.class);
        when(biller.getCode()).thenReturn("SKYCOM_POSTPAID");
        when(biller.getAccountLabel()).thenReturn("Mobile number");
        return biller;
    }

    @Test
    void stubFetchesAStableBillAndKnowsUnknownAccounts() {
        var stub = adapter(Mode.STUB, "https://bbps.example", Map.of());

        var bill = stub.fetch(biller(), "9876543210");
        assertThat(bill.amountDue().toMinorUnits()).isBetween(15_000L, 414_999L).isEqualTo(stub.fetch(biller(), "9876543210").amountDue().toMinorUnits());
        assertThat(bill.accountRefMasked()).endsWith("3210").doesNotContain("98765");
        assertThat(bill.dueDate()).isAfter(LocalDate.now());
        assertThatThrownBy(() -> stub.fetch(biller(), "9876540000")).isInstanceOf(BillerGateway.AccountNotFoundException.class);
    }

    @Test
    void stubPayDeclinesTimesOutOnceAndSucceeds() {
        var stub = adapter(Mode.STUB, "https://bbps.example", Map.of());
        UUID payment = UUID.randomUUID();

        assertThat(stub.pay(biller(), "9876543210", Money.inr("249.00"), UUID.randomUUID())).matches("BBPS[0-9A-F]{12}");
        assertThatThrownBy(() -> stub.pay(biller(), "9876543210", Money.inr("100.13"), UUID.randomUUID())).isInstanceOf(BillerGateway.BillerDeclinedException.class);
        assertThatThrownBy(() -> stub.pay(biller(), "9876543210", Money.inr("100.14"), payment)).isInstanceOf(BillerGateway.BillerUnavailableException.class);
        assertThat(stub.pay(biller(), "9876543210", Money.inr("100.14"), payment)).startsWith("BBPS");
    }

    @Test
    void livePaySendsTheIdempotentReference() {
        UUID payment = UUID.randomUUID();
        LIVE.stubFor(post(urlEqualTo("/bbps/v1/bills/pay")).withHeader("Authorization", equalTo("Bearer bbps-key"))
                .withRequestBody(equalToJson("{\"ref_id\":\"" + payment + "\",\"agent_id\":\"AG01\",\"biller_id\":\"SKYCOM_POSTPAID\","
                        + "\"customer_params\":{\"account_ref\":\"9876543210\"},\"amount\":24900,\"payment_mode\":\"Wallet\"}"))
                .willReturn(okJson("{\"status\":\"SUCCESS\",\"txn_ref_id\":\"CC01234567890\"}")));

        assertThat(adapter(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS).pay(biller(), "9876543210", Money.inr("249.00"), payment)).isEqualTo("CC01234567890");
    }

    @Test
    void livePendingAndOutagesAreRetryable() {
        LIVE.stubFor(post(urlEqualTo("/bbps/v1/bills/pay")).withRequestBody(equalToJson("{\"amount\":100}", true, true))
                .willReturn(okJson("{\"status\":\"PENDING\"}")));
        LIVE.stubFor(post(urlEqualTo("/bbps/v1/bills/fetch")).willReturn(aResponse().withStatus(503)));
        var live = adapter(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS);

        assertThatThrownBy(() -> live.pay(biller(), "1", Money.inr("1.00"), UUID.randomUUID())).isInstanceOf(BillerGateway.BillerUnavailableException.class);
        assertThatThrownBy(() -> live.fetch(biller(), "1")).isInstanceOf(BillerGateway.BillerUnavailableException.class);
    }
}
