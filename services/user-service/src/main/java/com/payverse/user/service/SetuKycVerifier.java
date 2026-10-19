package com.payverse.user.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.model.IdDocument;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Identity verification through Setu's KYC APIs ({@code x-client-id}, {@code x-client-secret},
 * {@code x-product-instance-id}; explicit user consent). PAN uses {@code /api/verify/pan}; the other documents use the
 * same request style on their own paths — confirm each path and field name against the product you subscribe to.
 * When the provider returns the holder's name, it must match the name the user entered. STUB mode answers with
 * Setu-shaped default responses ({@code provider-stubs/kyc.json}; numbers containing 9999 fail, like the old mock).
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.kyc.adapter", havingValue = "setu")
public class SetuKycVerifier implements KycVerifier {

    static final String PROVIDER = "kyc";
    static final String REASON = "KYC for a PayVerse wallet";

    private final RestClient http;

    public SetuKycVerifier(ProviderClients providers) {
        Provider settings = providers.settings(PROVIDER);
        this.http = providers.client(PROVIDER, List.of("client-id", "client-secret", "product-instance-id")).mutate()
                .defaultHeader("x-client-id", settings.credential("client-id"))
                .defaultHeader("x-client-secret", settings.credential("client-secret"))
                .defaultHeader("x-product-instance-id", settings.credential("product-instance-id"))
                .build();
    }

    @Override
    public Result verify(IdDocument document, String fullName, LocalDate dateOfBirth) {
        VerifyRequest request = switch (document.type()) {
            case PAN -> new VerifyRequest(document.number(), null, null, null, null, null);
            case VOTER_ID -> new VerifyRequest(null, document.number(), null, null, null, null);
            case DRIVING_LICENSE -> new VerifyRequest(null, null, document.number(), null, null, dateOfBirth);
            case PASSPORT -> new VerifyRequest(null, null, null, document.number(), null, dateOfBirth);
            case AADHAAR -> new VerifyRequest(null, null, null, null, document.number(), null);
        };
        String path = switch (document.type()) {
            case PAN -> "/api/verify/pan";
            case VOTER_ID -> "/api/verify/voter-id";
            case DRIVING_LICENSE -> "/api/verify/driving-licence";
            case PASSPORT -> "/api/verify/passport";
            case AADHAAR -> "/api/verify/aadhaar";
        };
        JsonNode response;
        try {
            response = http.post().uri(path).body(request).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            return new Result.Rejected(message(e.getResponseBodyAs(JsonNode.class), "Document details could not be verified"));
        } catch (RestClientException e) {
            throw new PayverseException(UserErrorCode.USER_KYC_PROVIDER_UNAVAILABLE, null);
        }
        if (response == null || !"SUCCESS".equalsIgnoreCase(response.path("verification").asString())) {
            return new Result.Rejected(message(response, "Document details could not be verified"));
        }
        String holder = response.path("data").path("full_name").asString("");
        if (!holder.isBlank() && !namesMatch(holder, fullName)) {
            return new Result.Rejected("The name does not match the document");
        }
        return new Result.Verified("setu-" + response.path("traceId").asString(response.path("id").asString("verified")));
    }

    /** Lenient match: every word of the shorter name appears in the longer one (handles middle names and order). */
    static boolean namesMatch(String a, String b) {
        Set<String> x = words(a);
        Set<String> y = words(b);
        return !x.isEmpty() && !y.isEmpty() && (x.size() <= y.size() ? y.containsAll(x) : x.containsAll(y));
    }

    private static Set<String> words(String name) {
        return Arrays.stream(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", " ").split("\\s+"))
                .filter(w -> w.length() > 1).collect(Collectors.toSet());
    }

    private static String message(JsonNode body, String fallback) {
        return body == null ? fallback : body.path("message").asString(fallback);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VerifyRequest(String pan, @JsonProperty("epic_number") String epicNumber, @JsonProperty("dl_number") String dlNumber,
                         @JsonProperty("file_number") String passportFileNumber, @JsonProperty("aadhaar_number") String aadhaarNumber,
                         LocalDate dob, String consent, String reason) {
        VerifyRequest(String pan, String epicNumber, String dlNumber, String passportFileNumber, String aadhaarNumber, LocalDate dob) {
            this(pan, epicNumber, dlNumber, passportFileNumber, aadhaarNumber, dob, "Y", REASON);
        }
    }
}
