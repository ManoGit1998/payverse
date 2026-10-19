package com.payverse.notification.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import com.payverse.notification.entity.Channel;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transactional SMS through MSG91 (Flow API v5). Indian DLT rules require a registered template: this uses one generic
 * template whose {@code ##message##} variable carries the rendered text (register more templates as needed).
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.notification-sms.adapter", havingValue = "msg91")
public class Msg91SmsProvider implements NotificationProvider {

    static final String PROVIDER = "notification-sms";

    private final RestClient http;
    private final Provider settings;
    private final RecipientDirectory directory;

    public Msg91SmsProvider(ProviderClients providers, RecipientDirectory directory) {
        this.settings = providers.settings(PROVIDER);
        this.directory = directory;
        this.http = providers.client(PROVIDER, List.of("auth-key", "template-generic")).mutate()
                .defaultHeader("authkey", settings.credential("auth-key")).build();
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public String send(Delivery delivery) {
        var contact = ExternalDelivery.contact(directory, settings, delivery);
        try {
            FlowResponse response = http.post().uri("/api/v5/flow")
                    .body(new FlowRequest(settings.credential("template-generic"), "0", List.of(new Recipient(contact.phone(), delivery.body()))))
                    .retrieve().body(FlowResponse.class);
            if (response == null || !"success".equalsIgnoreCase(response.type())) {
                throw new DeliveryFailedException("MSG91 rejected the SMS: " + (response == null ? "no response" : response.message()));
            }
            return "msg91-" + response.message();
        } catch (RestClientException e) {
            throw ExternalDelivery.failure("MSG91", e);
        }
    }

    record FlowRequest(@JsonProperty("template_id") String templateId, @JsonProperty("short_url") String shortUrl, List<Recipient> recipients) {
    }

    record Recipient(String mobiles, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FlowResponse(String type, String message) {
    }
}
