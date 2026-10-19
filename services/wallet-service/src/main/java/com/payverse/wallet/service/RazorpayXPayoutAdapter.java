package com.payverse.wallet.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import com.payverse.wallet.exception.WalletErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * RazorpayX Payouts (HTTP Basic, {@code X-Payout-Idempotency}): sends an IMPS payout from the PayVerse current account
 * to the user's bank account. Statuses queued/pending/processing/processed are accepted (the payout reference is
 * returned); rejected/failed/cancelled make the withdrawal fail and roll back. STUB mode answers with RazorpayX-shaped
 * default responses ({@code provider-stubs/payout.json}; amounts ending in .14 are rejected, like the old mock).
 * <p>
 * RazorpayX pays to a <em>fund account</em>. Register one (Contacts + Fund Accounts API) when a bank account is linked
 * and store its id; {@link #fundAccountId(UUID)} is the single place to look it up.
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.payout.adapter", havingValue = "razorpayx")
public class RazorpayXPayoutAdapter implements PayoutGateway {

    static final String PROVIDER = "payout";
    private static final Set<String> ACCEPTED = Set.of("queued", "pending", "processing", "processed", "scheduled");

    private final RestClient http;
    private final String accountNumber;

    public RazorpayXPayoutAdapter(ProviderClients providers) {
        Provider settings = providers.settings(PROVIDER);
        this.accountNumber = settings.credential("account-number");
        this.http = providers.client(PROVIDER, List.of("key-id", "key-secret", "account-number")).mutate()
                .defaultHeaders(h -> h.setBasicAuth(settings.credential("key-id"), settings.credential("key-secret")))
                .build();
    }

    @Override
    public String initiate(UUID userId, UUID bankAccountId, Money amount, String idempotencyKey) {
        try {
            Payout payout = http.post().uri("/v1/payouts")
                    .header("X-Payout-Idempotency", idempotencyKey)
                    .body(new PayoutRequest(accountNumber, fundAccountId(bankAccountId), amount.toMinorUnits(),
                            amount.currency().getCurrencyCode(), "IMPS", "payout", true, truncate(idempotencyKey, 40),
                            "PayVerse withdrawal", Map.of("user_id", userId.toString(), "bank_account_id", bankAccountId.toString())))
                    .retrieve().body(Payout.class);
            if (payout == null || !ACCEPTED.contains(payout.status())) {
                String reason = payout == null || payout.statusDetails() == null ? "Payout " + (payout == null ? "failed" : payout.status())
                        : payout.statusDetails().path("description").asString("Payout " + payout.status());
                throw new PayverseException(WalletErrorCode.WALLET_PAYOUT_FAILED, reason);
            }
            return payout.id();
        } catch (HttpClientErrorException e) {
            throw new PayverseException(WalletErrorCode.WALLET_PAYOUT_FAILED, description(e));
        } catch (RestClientException e) {
            throw new PayverseException(WalletErrorCode.WALLET_PAYOUT_FAILED, "Payout provider unavailable");
        }
    }

    /** The RazorpayX fund account of a linked bank account (to be stored when the account is linked). */
    static String fundAccountId(UUID bankAccountId) {
        return "fa_" + bankAccountId.toString().replace("-", "").substring(0, 14);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private static String description(HttpClientErrorException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            return body == null ? e.getStatusText() : body.path("error").path("description").asString(e.getStatusText());
        } catch (RuntimeException parse) {
            return e.getStatusText();
        }
    }

    record PayoutRequest(@JsonProperty("account_number") String accountNumber, @JsonProperty("fund_account_id") String fundAccountId,
                         long amount, String currency, String mode, String purpose,
                         @JsonProperty("queue_if_low_balance") boolean queueIfLowBalance, @JsonProperty("reference_id") String referenceId,
                         String narration, Map<String, String> notes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payout(String id, String status, String utr, @JsonProperty("status_details") JsonNode statusDetails) {
    }
}
