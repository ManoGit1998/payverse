package com.payverse.notification.service;

import com.payverse.common.web.provider.ProviderProperties.Provider;
import com.payverse.notification.service.NotificationProvider.DeliveryFailedException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Shared rules of the real delivery providers. */
final class ExternalDelivery {

    private ExternalDelivery() {
    }

    static RecipientDirectory.Contact contact(RecipientDirectory directory, Provider settings, NotificationProvider.Delivery delivery) {
        RecipientDirectory.Contact contact = directory.lookup(delivery.userId());
        if (settings.live() && contact.synthetic()) {
            throw new DeliveryFailedException("No contact details for user " + delivery.userId() + ": configure a RecipientDirectory before going live");
        }
        return contact;
    }

    static DeliveryFailedException failure(String provider, RestClientException e) {
        String detail = e instanceof RestClientResponseException r ? r.getStatusCode() + " " + r.getResponseBodyAsString() : e.getMessage();
        return new DeliveryFailedException(provider + " rejected or failed the delivery: " + detail);
    }
}
