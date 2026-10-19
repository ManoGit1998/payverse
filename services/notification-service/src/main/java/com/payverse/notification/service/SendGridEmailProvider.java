package com.payverse.notification.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import com.payverse.notification.entity.Channel;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** E-mail through Twilio SendGrid (v3 Mail Send, bearer API key; 202 Accepted with the message id in X-Message-Id). */
@Component
@ConditionalOnProperty(name = "payverse.providers.notification-email.adapter", havingValue = "sendgrid")
public class SendGridEmailProvider implements NotificationProvider {

    static final String PROVIDER = "notification-email";

    private final RestClient http;
    private final Provider settings;
    private final RecipientDirectory directory;

    public SendGridEmailProvider(ProviderClients providers, RecipientDirectory directory) {
        this.settings = providers.settings(PROVIDER);
        this.directory = directory;
        this.http = providers.client(PROVIDER, List.of("api-key", "from-email")).mutate()
                .defaultHeader("Authorization", "Bearer " + settings.credential("api-key")).build();
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public String send(Delivery delivery) {
        var contact = ExternalDelivery.contact(directory, settings, delivery);
        var mail = new Mail(List.of(new Personalization(List.of(new Address(contact.email(), null)))),
                new Address(settings.credentials().getOrDefault("from-email", "no-reply@payverse.example"), "PayVerse"),
                delivery.subject() == null ? "PayVerse" : delivery.subject(),
                List.of(new Content("text/plain", delivery.body())),
                Map.of("notification_id", delivery.notificationId().toString()));
        try {
            var response = http.post().uri("/v3/mail/send").body(mail).retrieve().toBodilessEntity();
            String id = response.getHeaders().getFirst("X-Message-Id");
            return "sendgrid-" + (id == null ? delivery.notificationId() : id);
        } catch (RestClientException e) {
            throw ExternalDelivery.failure("SendGrid", e);
        }
    }

    record Mail(List<Personalization> personalizations, Address from, String subject, List<Content> content,
                @JsonProperty("custom_args") Map<String, String> customArgs) {
    }

    record Personalization(List<Address> to) {
    }

    record Address(String email, String name) {
    }

    record Content(String type, String value) {
    }
}
