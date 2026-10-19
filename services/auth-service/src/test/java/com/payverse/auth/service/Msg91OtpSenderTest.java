package com.payverse.auth.service;

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
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties;
import com.payverse.common.web.provider.ProviderProperties.Mode;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class Msg91OtpSenderTest {

    static final WireMockServer LIVE = new WireMockServer(wireMockConfig().dynamicPort());
    static final Map<String, String> CREDENTIALS = Map.of("auth-key", "msg91-key", "template-login", "tpl-login", "template-pin-reset", "tpl-reset");

    @BeforeAll
    static void start() {
        LIVE.start();
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    static Msg91OtpSender sender(Mode mode, String baseUrl, Map<String, String> credentials) {
        var props = new ProviderProperties(Map.of("sms", new ProviderProperties.Provider("msg91", mode, URI.create(baseUrl), credentials, null, null)));
        return new Msg91OtpSender(new ProviderClients(props,
                new StaticListableBeanFactory(Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class), new JsonMapper()));
    }

    @Test
    void stubLogsTheCodeInTheDevFormat(CapturedOutput output) {
        sender(Mode.STUB, "https://control.msg91.com", Map.of()).send(MobileNumber.parse("9812345601"), "123456", OtpPurpose.LOGIN);

        assertThat(output.getOut()).containsPattern("\\[MOCK SMS\\] LOGIN OTP for \\*{6}5601 is 123456 \\(MSG91 STUB");
    }

    @Test
    void liveSendsTheFlowAndNeverLogsTheCode(CapturedOutput output) {
        LIVE.stubFor(post(urlEqualTo("/api/v5/flow")).withHeader("authkey", equalTo("msg91-key"))
                .willReturn(okJson("{\"type\":\"success\",\"message\":\"3763646c3058373530393832\"}")));

        sender(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS).send(MobileNumber.parse("9812345601"), "654321", OtpPurpose.PIN_RESET);

        LIVE.verify(postRequestedFor(urlEqualTo("/api/v5/flow")).withRequestBody(equalToJson(
                "{\"template_id\":\"tpl-reset\",\"short_url\":\"0\",\"recipients\":[{\"mobiles\":\"919812345601\",\"otp\":\"654321\"}]}")));
        assertThat(output.getOut()).doesNotContain("654321");
    }

    @Test
    void liveErrorIsReported() {
        LIVE.stubFor(post(urlEqualTo("/api/v5/flow")).withHeader("authkey", equalTo("bad"))
                .willReturn(okJson("{\"type\":\"error\",\"message\":\"Invalid template\"}")));

        assertThatThrownBy(() -> sender(Mode.LIVE, LIVE.baseUrl(), Map.of("auth-key", "bad", "template-login", "t", "template-pin-reset", "t"))
                .send(MobileNumber.parse("9812345601"), "111111", OtpPurpose.LOGIN))
                .hasMessageContaining("Invalid template");
    }
}
