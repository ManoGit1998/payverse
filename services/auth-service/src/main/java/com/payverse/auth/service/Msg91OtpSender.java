package com.payverse.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SMS OTPs through MSG91 (Flow API v5, {@code authkey} header, DLT-approved template with an {@code ##otp##} variable;
 * one template per purpose). In STUB mode no SMS leaves the system, so the code is also written to the log in the
 * {@code [MOCK SMS]} format that {@code make otp} and the e2e scripts read; in LIVE mode it is never logged.
 */
@Component
@ConditionalOnProperty(name = "payverse.auth.otp.sender", havingValue = "msg91")
public class Msg91OtpSender implements OtpSender {

    static final String PROVIDER = "sms";
    private static final Logger log = LoggerFactory.getLogger(Msg91OtpSender.class);

    private final RestClient http;
    private final Provider settings;

    public Msg91OtpSender(ProviderClients providers) {
        this.settings = providers.settings(PROVIDER);
        this.http = providers.client(PROVIDER, List.of("auth-key", "template-login", "template-pin-reset")).mutate()
                .defaultHeader("authkey", settings.credential("auth-key"))
                .build();
    }

    @Override
    public void send(MobileNumber mobile, String code, OtpPurpose purpose) {
        String template = settings.credential(purpose == OtpPurpose.LOGIN ? "template-login" : "template-pin-reset");
        FlowResponse response;
        try {
            response = http.post().uri("/api/v5/flow")
                    .body(new FlowRequest(template, "0", List.of(new Recipient("91" + mobile.value(), code))))
                    .retrieve().body(FlowResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("MSG91 unavailable: " + e.getMessage(), e);
        }
        if (response == null || !"success".equalsIgnoreCase(response.type())) {
            throw new IllegalStateException("MSG91 rejected the OTP SMS: " + (response == null ? "no response" : response.message()));
        }
        if (!settings.live()) {
            log.atInfo().addKeyValue("mock", "sms").addKeyValue("purpose", purpose.name())
                    .log("[MOCK SMS] {} OTP for {} is {} (MSG91 STUB, request {})", purpose, mobile.masked(), code, response.message());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FlowRequest(@JsonProperty("template_id") String templateId, @JsonProperty("short_url") String shortUrl, List<Recipient> recipients) {
    }

    record Recipient(String mobiles, String otp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FlowResponse(String type, String message) {
    }
}
