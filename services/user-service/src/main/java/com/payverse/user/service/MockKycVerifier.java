package com.payverse.user.service;

import com.payverse.user.model.IdDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * MOCK KYC provider. Approves every well-formed document <b>except</b> numbers containing
 * {@value #REJECT_MARKER}, which are rejected — use them to exercise the failure path
 * (e.g. PAN {@code ABCDE9999F}). Replace with a real KYC/OVD provider in production.
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.kyc.adapter", havingValue = "mock", matchIfMissing = true)
public class MockKycVerifier implements KycVerifier {

    static final String REJECT_MARKER = "9999";
    private static final Logger log = LoggerFactory.getLogger(MockKycVerifier.class);

    public MockKycVerifier() {
        log.warn("MockKycVerifier (MOCK) is active: documents are not verified with a real provider.");
    }

    @Override
    public Result verify(IdDocument document, String fullName, LocalDate dateOfBirth) {
        if (document.number().contains(REJECT_MARKER)) {
            return new Result.Rejected("Document details could not be verified (mock rule)");
        }
        return new Result.Verified("mock-kyc-" + UUID.randomUUID());
    }
}
