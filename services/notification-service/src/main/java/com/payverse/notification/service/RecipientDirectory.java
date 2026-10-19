package com.payverse.notification.service;

import java.util.UUID;

/**
 * Where real delivery providers get a user's contact details (phone, e-mail, push token). The default
 * {@link UnconfiguredRecipientDirectory} only produces placeholders, which STUB providers accept and LIVE providers
 * refuse — before going live, implement this with the user/auth services' contact data (and a push-token registry).
 */
public interface RecipientDirectory {

    /** @param synthetic placeholder values that must never reach a real provider */
    record Contact(String phone, String email, String pushToken, boolean synthetic) {
    }

    Contact lookup(UUID userId);
}
