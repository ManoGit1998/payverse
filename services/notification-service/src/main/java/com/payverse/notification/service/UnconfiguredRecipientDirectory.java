package com.payverse.notification.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Placeholder contacts (reserved .invalid domain, test number) until a real {@link RecipientDirectory} exists; mark the
 * real implementation {@code @Primary} and it takes over.
 */
@Component
public class UnconfiguredRecipientDirectory implements RecipientDirectory {

    @Override
    public Contact lookup(UUID userId) {
        return new Contact("910000000000", "user-" + userId + "@users.payverse.invalid", "stub-token-" + userId, true);
    }
}
