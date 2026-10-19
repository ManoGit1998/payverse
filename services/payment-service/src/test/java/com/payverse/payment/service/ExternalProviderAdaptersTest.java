package com.payverse.payment.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties;
import com.payverse.common.web.provider.ProviderProperties.Mode;
import com.payverse.payment.model.VpaHandle;
import com.payverse.payment.repository.VpaRepository;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ExternalProviderAdaptersTest {

    static final WireMockServer LIVE = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void start() {
        LIVE.start();
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    static ProviderClients clients(String name, String adapter, Mode mode, String baseUrl, Map<String, String> credentials) {
        var props = new ProviderProperties(Map.of(name, new ProviderProperties.Provider(adapter, mode, URI.create(baseUrl), credentials, null, null)));
        return new ProviderClients(props, new StaticListableBeanFactory(Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class),
                new JsonMapper());
    }

    // ---------------------------------------------------------------- Razorpay
    @Test
    void razorpayStubApprovesWithAPaymentId() {
        var pg = new RazorpayPaymentGatewayAdapter(clients("payment-gateway", "razorpay", Mode.STUB, "https://api.razorpay.com", Map.of()));

        var charge = pg.charge(Money.inr("250.00"), "UPI", UUID.randomUUID().toString());

        assertThat(charge).isInstanceOfSatisfying(PaymentGatewayProvider.Charge.Approved.class,
                approved -> assertThat(approved.reference()).matches("pay_[0-9A-F]{14}"));
        assertThat(pg.name()).isEqualTo("RAZORPAY");
    }

    @Test
    void razorpayStubDeclinesAmountsEndingIn13() {
        var pg = new RazorpayPaymentGatewayAdapter(clients("payment-gateway", "razorpay", Mode.STUB, "https://api.razorpay.com", Map.of()));

        assertThat(pg.charge(Money.inr("100.13"), "CARD", UUID.randomUUID().toString()))
                .isEqualTo(new PaymentGatewayProvider.Charge.Declined("Declined by issuer"));
    }

    @Test
    void razorpayLiveSendsTheOrderWithBasicAuthAndReadsCapturedPayment() {
        String key = UUID.randomUUID().toString();
        LIVE.stubFor(post(urlEqualTo("/v1/orders"))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder().encodeToString("rzp_test_key:secret".getBytes())))
                .withRequestBody(equalToJson("{\"amount\":25000,\"currency\":\"INR\",\"receipt\":\"" + key + "\"}", true, true))
                .willReturn(okJson("{\"id\":\"order_LIVE1\",\"entity\":\"order\",\"amount\":25000,\"status\":\"paid\"}")));
        LIVE.stubFor(get(urlEqualTo("/v1/orders/order_LIVE1/payments"))
                .willReturn(okJson("{\"entity\":\"collection\",\"count\":1,\"items\":[{\"id\":\"pay_LIVE1\",\"status\":\"captured\"}]}")));
        var pg = new RazorpayPaymentGatewayAdapter(clients("payment-gateway", "razorpay", Mode.LIVE, LIVE.baseUrl(),
                Map.of("key-id", "rzp_test_key", "key-secret", "secret")));

        assertThat(pg.charge(Money.inr("250.00"), "UPI", key)).isEqualTo(new PaymentGatewayProvider.Charge.Approved("pay_LIVE1"));
    }

    @Test
    void razorpayLiveErrorBecomesADecline() {
        LIVE.stubFor(post(urlEqualTo("/v1/orders")).withRequestBody(equalToJson("{\"amount\":99}", true, true))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Order amount less than minimum amount allowed\"}}")));
        var pg = new RazorpayPaymentGatewayAdapter(clients("payment-gateway", "razorpay", Mode.LIVE, LIVE.baseUrl(),
                Map.of("key-id", "k", "key-secret", "s")));

        assertThat(pg.charge(Money.inr("0.99"), "UPI", UUID.randomUUID().toString()))
                .isEqualTo(new PaymentGatewayProvider.Charge.Declined("Order amount less than minimum amount allowed"));
    }

    @Test
    void liveModeWithoutCredentialsFailsAtStartup() {
        assertThatThrownBy(() -> new RazorpayPaymentGatewayAdapter(clients("payment-gateway", "razorpay", Mode.LIVE, LIVE.baseUrl(), Map.of())))
                .hasMessageContaining("payverse.providers.payment-gateway.credentials.key-id");
    }

    // ---------------------------------------------------------------- UPI (PSP bank)
    @Test
    void upiStubSettlesWithA12DigitRrn() {
        var upi = new PspBankUpiSwitchAdapter(mock(VpaRepository.class), clients("upi-switch", "psp-bank", Mode.STUB, "https://upi.bank.example", Map.of()));

        assertThat(upi.settle(UUID.randomUUID(), new VpaHandle("asha@payverse"), new VpaHandle("ravi@payverse"), Money.inr("10.00")))
                .matches("\\d{12}");
    }

    @Test
    void upiExternalHandlesAreValidatedThenRefused() {
        var upi = new PspBankUpiSwitchAdapter(mock(VpaRepository.class), clients("upi-switch", "psp-bank", Mode.STUB, "https://upi.bank.example", Map.of()));

        assertThatThrownBy(() -> upi.resolve(new VpaHandle("friend@okhdfc"))).isInstanceOf(PayverseException.class)
                .hasMessageContaining("not enabled yet");
        assertThatThrownBy(() -> upi.resolve(new VpaHandle("unknown@okhdfc"))).isInstanceOf(PayverseException.class)
                .satisfies(e -> assertThat(((PayverseException) e).errorCode().code()).isEqualTo("PAYMENT_VPA_NOT_FOUND"));
    }

    @Test
    void upiLivePostsThePayment() {
        UUID txn = UUID.randomUUID();
        LIVE.stubFor(post(urlEqualTo("/upi/v1/payments")).withHeader("X-Api-Key", equalTo("key"))
                .willReturn(okJson("{\"status\":\"SUCCESS\",\"rrn\":\"612345678901\"}")));
        var upi = new PspBankUpiSwitchAdapter(mock(VpaRepository.class), clients("upi-switch", "psp-bank", Mode.LIVE, LIVE.baseUrl(),
                Map.of("merchant-id", "M1", "api-key", "key")));

        assertThat(upi.settle(txn, new VpaHandle("asha@payverse"), new VpaHandle("ravi@payverse"), Money.inr("10.00"))).isEqualTo("612345678901");
        LIVE.verify(postRequestedFor(urlEqualTo("/upi/v1/payments")).withRequestBody(equalToJson(
                "{\"txn_id\":\"" + txn + "\",\"payer_vpa\":\"asha@payverse\",\"payee_vpa\":\"ravi@payverse\",\"amount\":{\"value\":1000,\"currency\":\"INR\"},\"merchant_id\":\"M1\"}")));
    }
}
