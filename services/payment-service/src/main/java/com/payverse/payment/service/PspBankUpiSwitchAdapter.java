package com.payverse.payment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import com.payverse.payment.entity.Vpa;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.model.VpaHandle;
import com.payverse.payment.repository.VpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * UPI through a sponsor/PSP bank's UPI API (the bank connects to the NPCI switch). PayVerse's own {@code @payverse}
 * handles resolve locally; other handles are validated with the bank ("validate address") and then refused, because
 * paying users of other apps is not enabled yet. Settlement posts the payment and returns the switch RRN.
 * <p>
 * Every sponsor bank has its own UPI API contract; endpoints and fields below follow the common shape (validate VPA,
 * pay, status with RRN) — map them to the bank's specification when onboarding. STUB mode answers with default
 * responses from {@code provider-stubs/upi-switch.json}.
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.upi-switch.adapter", havingValue = "psp-bank")
public class PspBankUpiSwitchAdapter implements UpiSwitch {

    static final String PROVIDER = "upi-switch";

    private final VpaRepository vpas;
    private final RestClient http;
    private final String merchantId;

    public PspBankUpiSwitchAdapter(VpaRepository vpas, ProviderClients providers) {
        this.vpas = vpas;
        Provider settings = providers.settings(PROVIDER);
        this.merchantId = settings.credential("merchant-id");
        this.http = providers.client(PROVIDER, List.of("merchant-id", "api-key")).mutate()
                .defaultHeader("X-Merchant-Id", merchantId)
                .defaultHeader("X-Api-Key", settings.credential("api-key"))
                .build();
    }

    @Override
    public UUID resolve(VpaHandle vpa) {
        if (vpa.isPayverse()) {
            return vpas.findByHandle(vpa.value()).filter(Vpa::isActive).map(Vpa::getUserId)
                    .orElseThrow(() -> new PayverseException(PaymentErrorCode.PAYMENT_VPA_NOT_FOUND, null));
        }
        ValidateResponse validation = http.post().uri("/upi/v1/vpa/validate").body(new ValidateRequest(vpa.value(), merchantId))
                .retrieve().body(ValidateResponse.class);
        if (validation == null || !"VALID".equals(validation.status())) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_VPA_NOT_FOUND, null);
        }
        throw new PayverseException(PaymentErrorCode.PAYMENT_VPA_EXTERNAL_UNSUPPORTED,
                "Paying " + vpa.psp() + " UPI IDs (" + validation.payeeName() + ") is not enabled yet");
    }

    @Override
    public String settle(UUID transactionId, VpaHandle payer, VpaHandle payee, Money amount) {
        PayResponse response = http.post().uri("/upi/v1/payments")
                .body(new PayRequest(transactionId.toString(), payer.value(), payee.value(),
                        new Amount(amount.toMinorUnits(), amount.currency().getCurrencyCode()), merchantId))
                .retrieve().body(PayResponse.class);
        if (response == null || response.rrn() == null || "FAILURE".equals(response.status())) {
            throw new IllegalStateException("UPI settlement failed for " + transactionId + ": " + (response == null ? "no response" : response.message()));
        }
        return response.rrn();
    }

    record ValidateRequest(String vpa, @JsonProperty("merchant_id") String merchantId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ValidateResponse(String status, @JsonProperty("payee_name") String payeeName) {
    }

    record Amount(long value, String currency) {
    }

    record PayRequest(@JsonProperty("txn_id") String txnId, @JsonProperty("payer_vpa") String payerVpa,
                      @JsonProperty("payee_vpa") String payeeVpa, Amount amount, @JsonProperty("merchant_id") String merchantId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PayResponse(String status, String rrn, @JsonProperty("npci_txn_id") String npciTxnId, String message) {
    }
}
