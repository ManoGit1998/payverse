package com.payverse.common.web.client;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServiceClientSupportTest {

    @Test
    void remoteProblemKeepsCodeStatusAndExtraProperties() throws Exception {
        MockClientHttpResponse response = new MockClientHttpResponse(("""
                {"type":"x","title":"Incorrect transaction PIN","status":422,"detail":"nope","code":"AUTH_PIN_INCORRECT",
                 "remainingAttempts":3,"timestamp":"t","correlationId":"c"}""").getBytes(), HttpStatus.UNPROCESSABLE_CONTENT);
        PayverseException e = RemoteProblemHandler.toException("auth-service", response);
        assertThat(e.errorCode().code()).isEqualTo("AUTH_PIN_INCORRECT");
        assertThat(e.errorCode().httpStatus()).isEqualTo(422);
        assertThat(e.errorCode().title()).isEqualTo("Incorrect transaction PIN");
        assertThat(e.getMessage()).isEqualTo("nope");
        assertThat(e.properties()).containsEntry("remainingAttempts", 3).doesNotContainKeys("timestamp", "correlationId");
    }

    @Test
    void serverErrorsAndGarbageBecomeDownstreamUnavailable() throws Exception {
        assertThat(RemoteProblemHandler.toException("x", new MockClientHttpResponse(new byte[0], HttpStatus.BAD_GATEWAY)).errorCode())
                .isEqualTo(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertThat(RemoteProblemHandler.toException("x", new MockClientHttpResponse("<html>".getBytes(), HttpStatus.NOT_FOUND)).errorCode())
                .isEqualTo(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertThat(RemoteProblemHandler.toException("x", new MockClientHttpResponse("{}".getBytes(), HttpStatus.NOT_FOUND)).errorCode())
                .isEqualTo(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
    }

    @Test
    void serviceTokenIsCachedUntilShortlyBeforeExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://auth/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=wallet-service")))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"token_type\":\"Bearer\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://auth/token"))
                .andRespond(withSuccess("{\"access_token\":\"t2\",\"token_type\":\"Bearer\",\"expires_in\":300}", MediaType.APPLICATION_JSON));

        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-14T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        ServiceTokenProvider provider = new ServiceTokenProvider(builder.build(),
                new ServiceClientProperties("wallet-service", "secret", "http://auth/token"), clock);

        assertThat(provider.token()).isEqualTo("t1");
        now.set(now.get().plusSeconds(200));
        assertThat(provider.token()).isEqualTo("t1");
        now.set(now.get().plusSeconds(80)); // within the 30s refresh margin
        assertThat(provider.token()).isEqualTo("t2");
        server.verify();
        assertThat(new ServiceClientProperties("a", "s", "u").toString()).doesNotContain("s,");
    }
}
