package com.payverse.bill.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.bill.entity.Biller;
import com.payverse.bill.model.FetchedBill;
import com.payverse.common.money.Money;
import com.payverse.common.web.masking.PiiMasker;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Bills through a BBPS biller-aggregator (a Bharat Connect / BBPS COU such as Setu, Euronet or a bank). Fetch returns
 * the bill; pay is idempotent per {@code ref_id} (our payment id), so retries after a timeout never pay twice.
 * FAILURE → declined (refund); PENDING, 5xx and timeouts → unavailable (retried later with the same id).
 * <p>
 * Each aggregator has its own contract (sync or callback-based, field names, biller ids); the request/response mapping
 * is contained in this class — adapt it when onboarding. STUB mode answers with default responses from
 * {@code provider-stubs/biller.json} (accounts ending 0000 unknown, amounts ending .13 declined, .14 time out once).
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.biller.adapter", havingValue = "bbps")
public class BbpsBillerAdapter implements BillerGateway {

    static final String PROVIDER = "biller";

    private final RestClient http;
    private final String agentId;

    public BbpsBillerAdapter(ProviderClients providers) {
        Provider settings = providers.settings(PROVIDER);
        this.agentId = settings.credential("agent-id");
        this.http = providers.client(PROVIDER, List.of("api-key", "agent-id")).mutate()
                .defaultHeader("Authorization", "Bearer " + settings.credential("api-key"))
                .build();
    }

    @Override
    public FetchedBill fetch(Biller biller, String accountRef) {
        JsonNode response = call("/bbps/v1/bills/fetch",
                new FetchRequest(UUID.randomUUID().toString(), agentId, biller.getCode(), Map.of("account_ref", accountRef)));
        if (!"SUCCESS".equals(response.path("status").asString())) {
            String code = response.path("error").path("code").asString();
            if ("ACCOUNT_NOT_FOUND".equals(code) || "INVALID_CUSTOMER".equals(code)) {
                throw new AccountNotFoundException("No " + biller.getAccountLabel().toLowerCase() + " " + PiiMasker.maskAccountNumber(accountRef));
            }
            throw new BillerUnavailableException("Bill fetch failed: " + response.path("error").path("message").asString(code));
        }
        JsonNode bill = response.path("bill");
        return new FetchedBill(biller.getCode(), PiiMasker.maskAccountNumber(accountRef), bill.path("customer_name").asString(),
                bill.path("bill_number").asString(), Money.ofMinor(bill.path("amount").asLong(), Money.INR),
                LocalDate.parse(bill.path("due_date").asString()));
    }

    @Override
    public String pay(Biller biller, String accountRef, Money amount, UUID paymentId) {
        JsonNode response = call("/bbps/v1/bills/pay", new PayRequest(paymentId.toString(), agentId, biller.getCode(),
                Map.of("account_ref", accountRef), amount.toMinorUnits(), "Wallet"));
        return switch (response.path("status").asString()) {
            case "SUCCESS" -> response.path("txn_ref_id").asString();
            case "FAILURE" -> throw new BillerDeclinedException(response.path("error").path("message").asString("Declined by the biller"));
            default -> throw new BillerUnavailableException("Bill payment " + paymentId + " is " + response.path("status").asString("unknown"));
        };
    }

    private JsonNode call(String path, Object body) {
        try {
            return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new BillerUnavailableException("Biller aggregator unavailable: " + e.getMessage());
        } catch (RestClientResponseException e) {
            JsonNode error = e.getResponseBodyAs(JsonNode.class);
            throw new BillerDeclinedException(error == null ? e.getStatusText() : error.path("error").path("message").asString(e.getStatusText()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FetchRequest(@JsonProperty("ref_id") String refId, @JsonProperty("agent_id") String agentId,
                        @JsonProperty("biller_id") String billerId, @JsonProperty("customer_params") Map<String, String> customerParams) {
    }

    record PayRequest(@JsonProperty("ref_id") String refId, @JsonProperty("agent_id") String agentId,
                      @JsonProperty("biller_id") String billerId, @JsonProperty("customer_params") Map<String, String> customerParams,
                      long amount, @JsonProperty("payment_mode") String paymentMode) {
    }
}
