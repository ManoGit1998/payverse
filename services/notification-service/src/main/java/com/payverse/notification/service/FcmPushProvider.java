package com.payverse.notification.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import com.payverse.notification.entity.Channel;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Push notifications through Firebase Cloud Messaging (HTTP v1 {@code projects/<id>/messages:send}). The bearer token
 * is an OAuth2 access token of a Firebase service account ({@code access-token} credential); in production mint and
 * refresh it from the service-account key (e.g. Google Auth Library) instead of configuring it statically.
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.notification-push.adapter", havingValue = "fcm")
public class FcmPushProvider implements NotificationProvider {

    static final String PROVIDER = "notification-push";

    private final RestClient http;
    private final Provider settings;
    private final RecipientDirectory directory;

    public FcmPushProvider(ProviderClients providers, RecipientDirectory directory) {
        this.settings = providers.settings(PROVIDER);
        this.directory = directory;
        this.http = providers.client(PROVIDER, List.of("project-id", "access-token")).mutate()
                .defaultHeader("Authorization", "Bearer " + settings.credential("access-token")).build();
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public String send(Delivery delivery) {
        var contact = ExternalDelivery.contact(directory, settings, delivery);
        var message = Map.of("message", Map.of(
                "token", contact.pushToken(),
                "notification", Map.of("title", delivery.subject() == null ? "PayVerse" : delivery.subject(), "body", delivery.body()),
                "data", Map.of("notification_id", delivery.notificationId().toString())));
        try {
            SendResponse response = http.post().uri("/v1/projects/{project}/messages:send", settings.credential("project-id"))
                    .body(message).retrieve().body(SendResponse.class);
            if (response == null || response.name() == null) {
                throw new DeliveryFailedException("FCM returned no message name");
            }
            return "fcm-" + response.name().substring(response.name().lastIndexOf('/') + 1);
        } catch (RestClientException e) {
            throw ExternalDelivery.failure("FCM", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendResponse(String name) {
    }
}
