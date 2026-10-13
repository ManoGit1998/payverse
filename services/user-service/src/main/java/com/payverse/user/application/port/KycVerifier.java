package com.payverse.user.application.port;

import com.payverse.user.domain.IdDocument;

import java.time.LocalDate;

/** Verifies an identity document with a KYC provider ({@code MockKycVerifier} locally). */
public interface KycVerifier {

    sealed interface Result {
        record Verified(String providerReference) implements Result {
        }

        record Rejected(String reason) implements Result {
        }
    }

    Result verify(IdDocument document, String fullName, LocalDate dateOfBirth);
}
