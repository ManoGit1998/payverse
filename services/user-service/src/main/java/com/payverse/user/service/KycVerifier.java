package com.payverse.user.service;

import com.payverse.user.model.IdDocument;

import java.time.LocalDate;

/** Verifies an identity document with a KYC provider ({@link MockKycVerifier} locally). */
public interface KycVerifier {

    sealed interface Result {
        record Verified(String providerReference) implements Result {
        }

        record Rejected(String reason) implements Result {
        }
    }

    Result verify(IdDocument document, String fullName, LocalDate dateOfBirth);
}
