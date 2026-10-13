package com.payverse.user.application.port;

import java.util.UUID;

/** Stores uploaded KYC documents ({@code MockDocumentStorage} locally). */
public interface DocumentStorage {

    record StoredDocument(String reference, String sha256) {
    }

    StoredDocument store(UUID userId, byte[] content, String contentType);
}
