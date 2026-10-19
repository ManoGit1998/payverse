package com.payverse.user.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties;
import com.payverse.common.web.provider.ProviderProperties.Mode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class S3DocumentStorageTest {

    static final WireMockServer LIVE = new WireMockServer(wireMockConfig().dynamicPort());
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T06:30:00Z"), ZoneOffset.UTC);
    static final Map<String, String> CREDENTIALS = Map.of("access-key-id", "AKIAIOSFODNN7EXAMPLE",
            "secret-access-key", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "region", "ap-south-1", "bucket", "kyc-bucket");
    static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void start() {
        LIVE.start();
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    static S3DocumentStorage storage(Mode mode, String baseUrl, Map<String, String> credentials) {
        var props = new ProviderProperties(Map.of("document-storage", new ProviderProperties.Provider("s3", mode, URI.create(baseUrl), credentials, null, null)));
        return new S3DocumentStorage(new ProviderClients(props,
                new StaticListableBeanFactory(Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class), new JsonMapper()), CLOCK);
    }

    @Test
    void stubStoresAndReturnsTheObjectReference() {
        UUID user = UUID.randomUUID();
        var stored = storage(Mode.STUB, "https://kyc-bucket.s3.ap-south-1.amazonaws.com", Map.of()).store(user, PDF, "application/pdf");

        assertThat(stored.reference()).startsWith("s3://stub-bucket/kyc/" + user + "/");
        assertThat(stored.sha256()).hasSize(64);
    }

    @Test
    void livePutsASignedEncryptedObject() {
        LIVE.stubFor(put(urlMatching("/kyc/.+")).willReturn(aResponse().withStatus(200).withHeader("ETag", "\"abc\"")));
        UUID user = UUID.randomUUID();

        var stored = storage(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS).store(user, PDF, "application/pdf");

        assertThat(stored.reference()).startsWith("s3://kyc-bucket/kyc/" + user + "/");
        LIVE.verify(putRequestedFor(urlMatching("/kyc/" + user + "/[0-9a-f-]{36}"))
                .withHeader("x-amz-date", equalTo("20261019T063000Z"))
                .withHeader("x-amz-server-side-encryption", equalTo("aws:kms"))
                .withHeader("x-amz-content-sha256", equalTo(stored.sha256()))
                .withHeader("Authorization", matching("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261019/ap-south-1/s3/aws4_request, "
                        + "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date;x-amz-server-side-encryption, Signature=[0-9a-f]{64}")));
    }

    @Test
    void signingKeyMatchesTheAwsReferenceVector() {
        // AWS "Examples of how to derive a signing key for Signature Version 4"
        assertThat(java.util.HexFormat.of().formatHex(S3DocumentStorage.signingKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20120215", "us-east-1", "iam")))
                .isEqualTo("f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d");
    }

    @Test
    void signatureDependsOnEverySignedValue() {
        var s3 = storage(Mode.LIVE, LIVE.baseUrl(), CREDENTIALS);
        String base = s3.authorization("PUT", "/kyc/a", "host", "application/pdf", "h", "20261019T063000Z");

        assertThat(s3.authorization("PUT", "/kyc/a", "host", "application/pdf", "h", "20261019T063000Z")).isEqualTo(base);
        assertThat(s3.authorization("PUT", "/kyc/b", "host", "application/pdf", "h", "20261019T063000Z")).isNotEqualTo(base);
        assertThat(s3.authorization("PUT", "/kyc/a", "host", "image/png", "h", "20261019T063000Z")).isNotEqualTo(base);
    }
}
