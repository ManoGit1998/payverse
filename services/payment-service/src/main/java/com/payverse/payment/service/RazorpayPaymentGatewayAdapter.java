package com.payverse.payment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.common.money.Money;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Razorpay acquirer (Orders API, HTTP Basic with key id/secret): creates an order for the amount and reads the order's
 * payments; a captured payment approves the charge. In LIVE mode the customer completes the payment in Razorpay
 * Checkout (web/app) against the returned order id before the payments call — until then the order has no captured
 * payment and the charge is reported as declined. STUB mode answers with Razorpay-shaped default responses
 * ({@code provider-stubs/payment-gateway.json}; amounts ending in .13 fail, like the old mock).
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.payment-gateway.adapter", havingValue = "razorpay")
public class RazorpayPaymentGatewayAdapter implements PaymentGatewayProvider {

    static final String PROVIDER = "payment-gateway";
    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGatewayAdapter.class);

    private final RestClient http;

    public RazorpayPaymentGatewayAdapter(ProviderClients providers) {
        Provider settings = providers.settings(PROVIDER);
        this.http = providers.client(PROVIDER, List.of("key-id", "key-secret")).mutate()
                .defaultHeaders(h -> h.setBasicAuth(settings.credential("key-id"), settings.credential("key-secret")))
                .build();
    }

    @Override
    public String name() {
        return "RAZORPAY";
    }

    @Override
    public Charge charge(Money amount, String method, String idempotencyKey) {
        try {
            Order order = http.post().uri("/v1/orders")
                    .body(new OrderRequest(amount.toMinorUnits(), amount.currency().getCurrencyCode(), receipt(idempotencyKey),
                            Map.of("method", method, "idempotency_key", idempotencyKey)))
                    .retrieve().body(Order.class);
            Payments payments = http.get().uri("/v1/orders/{id}/payments", order.id()).retrieve().body(Payments.class);
            List<Payment> items = payments == null || payments.items() == null ? List.of() : payments.items();
            Optional<Payment> captured = items.stream().filter(p -> "captured".equals(p.status())).findFirst();
            if (captured.isPresent()) {
                return new Charge.Approved(captured.get().id());
            }
            String reason = items.isEmpty() ? "Payment not completed for order " + order.id()
                    : Optional.ofNullable(items.getLast().errorDescription()).orElse("Payment " + items.getLast().status());
            log.info("Razorpay order {} not captured: {}", order.id(), reason);
            return new Charge.Declined(reason);
        } catch (HttpClientErrorException e) {
            return new Charge.Declined(errorDescription(e));
        } catch (RestClientException e) {
            throw new IllegalStateException("Razorpay unavailable: " + e.getMessage(), e);
        }
    }

    /** Razorpay receipts are at most 40 characters. */
    private static String receipt(String idempotencyKey) {
        return idempotencyKey.length() <= 40 ? idempotencyKey : idempotencyKey.substring(0, 40);
    }

    private static String errorDescription(HttpClientErrorException e) {
        try {
            JsonNode error = e.getResponseBodyAs(JsonNode.class);
            return error != null && error.path("error").has("description") ? error.path("error").path("description").asString() : e.getStatusText();
        } catch (RuntimeException parse) {
            return e.getStatusText();
        }
    }

    record OrderRequest(long amount, String currency, String receipt, Map<String, String> notes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Order(String id, String status, long amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payment(String id, String status, @JsonProperty("error_code") String errorCode,
                   @JsonProperty("error_description") String errorDescription) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payments(List<Payment> items) {
    }
}
