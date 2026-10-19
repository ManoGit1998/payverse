package com.payverse.notification.service;

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
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties;
import com.payverse.common.web.provider.ProviderProperties.Mode;
import com.payverse.notification.service.NotificationProvider.Delivery;
import com.payverse.notification.service.NotificationProvider.DeliveryFailedException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ExternalNotificationProvidersTest {

    static final WireMockServer LIVE = new WireMockServer(wireMockConfig().dynamicPort());
    static final RecipientDirectory REAL = id -> new RecipientDirectory.Contact("919812345601", "asha@example.com", "device-token-1", false);

    @BeforeAll
    static void start() {
        LIVE.start();
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    static ProviderClients clients(String name, Mode mode, String baseUrl, Map<String, String> credentials) {
        var props = new ProviderProperties(Map.of(name, new ProviderProperties.Provider("x", mode, URI.create(baseUrl), credentials, null, null)));
        return new ProviderClients(props, new StaticListableBeanFactory(Map.of("b", RestClient.builder())).getBeanProvider(RestClient.Builder.class),
                new JsonMapper());
    }

    static Delivery delivery() {
        return new Delivery(UUID.randomUUID(), UUID.randomUUID(), "Payment sent", "INR 50.00 sent to Ravi");
    }

    @Test
    void stubProvidersDeliverToPlaceholderContacts() {
        var directory = new UnconfiguredRecipientDirectory();

        assertThat(new Msg91SmsProvider(clients("notification-sms", Mode.STUB, "https://control.msg91.com", Map.of()), directory).send(delivery()))
                .startsWith("msg91-");
        assertThat(new SendGridEmailProvider(clients("notification-email", Mode.STUB, "https://api.sendgrid.com", Map.of()), directory).send(delivery()))
                .matches("sendgrid-[0-9A-F]{22}");
        assertThat(new FcmPushProvider(clients("notification-push", Mode.STUB, "https://fcm.googleapis.com", Map.of()), directory).send(delivery()))
                .startsWith("fcm-0:");
    }

    @Test
    void liveRefusesPlaceholderContacts() {
        var sms = new Msg91SmsProvider(clients("notification-sms", Mode.LIVE, LIVE.baseUrl(), Map.of("auth-key", "k", "template-generic", "t")),
                new UnconfiguredRecipientDirectory());

        assertThatThrownBy(() -> sms.send(delivery())).isInstanceOf(DeliveryFailedException.class).hasMessageContaining("RecipientDirectory");
    }

    @Test
    void sendGridLiveSendsTheMail() {
        LIVE.stubFor(post(urlEqualTo("/v3/mail/send")).withHeader("Authorization", equalTo("Bearer SG.key"))
                .willReturn(aResponse().withStatus(202).withHeader("X-Message-Id", "msg-123")));
        var email = new SendGridEmailProvider(clients("notification-email", Mode.LIVE, LIVE.baseUrl(),
                Map.of("api-key", "SG.key", "from-email", "alerts@payverse.example")), REAL);

        assertThat(email.send(delivery())).isEqualTo("sendgrid-msg-123");
        LIVE.verify(postRequestedFor(urlEqualTo("/v3/mail/send")).withRequestBody(equalToJson(
                "{\"personalizations\":[{\"to\":[{\"email\":\"asha@example.com\"}]}],\"from\":{\"email\":\"alerts@payverse.example\",\"name\":\"PayVerse\"},"
                        + "\"subject\":\"Payment sent\",\"content\":[{\"type\":\"text/plain\",\"value\":\"INR 50.00 sent to Ravi\"}]}", true, true)));
    }

    @Test
    void fcmLiveSendsToTheDeviceToken() {
        LIVE.stubFor(post(urlEqualTo("/v1/projects/payverse-app/messages:send")).withHeader("Authorization", equalTo("Bearer ya29.token"))
                .withRequestBody(equalToJson("{\"message\":{\"token\":\"device-token-1\",\"notification\":{\"title\":\"Payment sent\"}}}", true, true))
                .willReturn(okJson("{\"name\":\"projects/payverse-app/messages/0:1500415314455276%31bd1c9631bd1c96\"}")));
        var push = new FcmPushProvider(clients("notification-push", Mode.LIVE, LIVE.baseUrl(),
                Map.of("project-id", "payverse-app", "access-token", "ya29.token")), REAL);

        assertThat(push.send(delivery())).isEqualTo("fcm-0:1500415314455276%31bd1c9631bd1c96");
    }

    @Test
    void msg91LiveFailureIsRetryable() {
        LIVE.stubFor(post(urlEqualTo("/api/v5/flow")).willReturn(aResponse().withStatus(500)));
        var sms = new Msg91SmsProvider(clients("notification-sms", Mode.LIVE, LIVE.baseUrl(), Map.of("auth-key", "k", "template-generic", "t")), REAL);

        assertThatThrownBy(() -> sms.send(delivery())).isInstanceOf(DeliveryFailedException.class).hasMessageContaining("MSG91");
    }
}
