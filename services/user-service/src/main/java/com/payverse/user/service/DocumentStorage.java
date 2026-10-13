package com.payverse.user.service;

import java.util.UUID;

/** Stores uploaded KYC documents ({@link MockDocumentStorage} locally). */
public interface DocumentStorage {

    record StoredDocument(String reference, String sha256) {
    }

    StoredDocument store(UUID userId, byte[] content, String contentType);
}
