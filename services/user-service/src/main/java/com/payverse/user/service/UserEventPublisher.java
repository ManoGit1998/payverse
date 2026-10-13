package com.payverse.user.service;

import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.user.entity.BankAccount;
import com.payverse.user.entity.KycTier;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Publishes user-service events via the transactional outbox. Call inside the business transaction. */
@Service
public class UserEventPublisher {

    private final OutboxPublisher outbox;

    public UserEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void kycUpgraded(UUID userId, KycTier from, KycTier to) {
        outbox.publish(EventType.KYC_UPGRADED, userId.toString(), new UserEvents.KycUpgraded(userId, from.name(), to.name()));
    }

    public void bankAccountLinked(BankAccount account) {
        outbox.publish(EventType.BANK_ACCOUNT_LINKED, account.getId().toString(),
                new UserEvents.BankAccountLinked(account.getUserId(), account.getId(), account.getMaskedNumber(), account.getIfsc()));
    }
}
