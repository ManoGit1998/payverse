package com.payverse.user.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties;
import com.payverse.common.web.provider.ProviderProperties.Mode;
import com.payverse.user.entity.IdDocumentType;
import com.payverse.user.model.IdDocument;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class SetuKycVerifierTest {

    static final WireMockServer LIVE = new WireMockServer(wireMockConfig().dynamicPort());
    static final Map<String, String> CREDENTIALS = Map.of("client-id", "cid", "client-secret", "secret", "product-instance-id", "pid");
    static final LocalDate DOB = LocalDate.of(1994, 5, 12);

    @BeforeAll
    static void start() {
        LIVE.start();
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    static SetuKycVerifier verifier(Mode mode, String baseUrl, Map<String, String> credentials) {
        var props = new ProviderProperties(Map.of("kyc", new ProviderProperties.Provider("setu", mode, URI.create(baseUrl), credentials, null, null)));
        return new SetuKycVerifier(new ProviderClients(props,
                new StaticListableBeanFactory(Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class), new JsonMapper()));
    }

    @Test
    void stubVerifiesAndRejectsTheTestMarker() {
        var stub = verifier(Mode.STUB, "https://dg-sandbox.setu.co", Map.of());

        assertThat(stub.verify(new IdDocument(IdDocumentType.PAN, "ABCDE1234F"), "Asha Verma", DOB)).isInstanceOf(KycVerifier.Result.Verified.class);
        assertThat(stub.verify(new IdDocument(IdDocumentType.PAN, "ABCDE9999F"), "Asha Verma", DOB))
                .isEqualTo(new KycVerifier.Result.Rejected("PAN is invalid"));
        assertThat(stub.verify(new IdDocument(IdDocumentType.DRIVING_LICENSE, "MH0120190009999"), "Asha Verma", DOB))
                .isInstanceOf(KycVerifier.Result.Rejected.class);
        assertThat(stub.verify(new IdDocument(IdDocumentType.AADHAAR, com.payverse.user.model.TestAadhaar.random()), "Asha Verma", DOB)).isInstanceOf(KycVerifier.Result.Verified.class);
    }

    @Test
    void liveVerifiesPanWithConsentAndChecksTheName() {
        LIVE.stubFor(post(urlEqualTo("/api/verify/pan")).withHeader("x-client-id", equalTo("cid")).withHeader("x-product-instance-id", equalTo("pid"))
                .withRequestBody(equalToJson("{\"pan\":\"ABCDE1234F\",\"consent\":\"Y\",\"reason\":\"KYC for a PayVerse wallet\"}"))
                .willReturn(okJson("{\"verification\":\"SUCCESS\",\"message\":\"PAN is valid\",\"traceId\":\"1-abc\",\"data\":{\"full_name\":\"ASHA KUMARI VERMA\"}}")));
        var live = verifier(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS);

        assertThat(live.verify(new IdDocument(IdDocumentType.PAN, "ABCDE1234F"), "Asha Verma", DOB))
                .isEqualTo(new KycVerifier.Result.Verified("setu-1-abc"));
        assertThat(live.verify(new IdDocument(IdDocumentType.PAN, "ABCDE1234F"), "Ravi Sharma", DOB))
                .isEqualTo(new KycVerifier.Result.Rejected("The name does not match the document"));
    }

    @Test
    void liveDrivingLicenceSendsTheDateOfBirth() {
        LIVE.stubFor(post(urlEqualTo("/api/verify/driving-licence"))
                .withRequestBody(equalToJson("{\"dl_number\":\"MH0120190001234\",\"dob\":\"1994-05-12\",\"consent\":\"Y\"}", true, true))
                .willReturn(okJson("{\"verification\":\"SUCCESS\",\"traceId\":\"1-dl\"}")));

        assertThat(verifier(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS).verify(new IdDocument(IdDocumentType.DRIVING_LICENSE, "MH0120190001234"), "Asha", DOB))
                .isEqualTo(new KycVerifier.Result.Verified("setu-1-dl"));
    }

    @Test
    void liveOutageIsRetryableNotARejection() {
        LIVE.stubFor(post(urlEqualTo("/api/verify/voter-id")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> verifier(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS).verify(new IdDocument(IdDocumentType.VOTER_ID, "ABC1234567"), "Asha", DOB))
                .isInstanceOf(PayverseException.class)
                .satisfies(e -> assertThat(((PayverseException) e).errorCode().code()).isEqualTo("USER_KYC_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void namesMatchIgnoresOrderCaseAndMiddleNames() {
        assertThat(SetuKycVerifier.namesMatch("ASHA KUMARI VERMA", "Asha Verma")).isTrue();
        assertThat(SetuKycVerifier.namesMatch("Verma, Asha", "asha verma")).isTrue();
        assertThat(SetuKycVerifier.namesMatch("Asha Verma", "Ravi Verma")).isFalse();
    }
}
