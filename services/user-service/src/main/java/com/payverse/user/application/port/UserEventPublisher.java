package com.payverse.user.application.port;

import com.payverse.user.domain.BankAccount;
import com.payverse.user.domain.KycTier;

import java.util.UUID;

/** Publishes user-service events via the transactional outbox. Call inside the business transaction. */
public interface UserEventPublisher {

    void kycUpgraded(UUID userId, KycTier from, KycTier to);

    void bankAccountLinked(BankAccount account);
}
