package com.payverse.user.infrastructure.events;

import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.user.application.port.UserEventPublisher;
import com.payverse.user.domain.BankAccount;
import com.payverse.user.domain.KycTier;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class OutboxUserEventPublisher implements UserEventPublisher {

    private final OutboxPublisher outbox;

    OutboxUserEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void kycUpgraded(UUID userId, KycTier from, KycTier to) {
        outbox.publish(EventType.KYC_UPGRADED, userId.toString(), new UserEvents.KycUpgraded(userId, from.name(), to.name()));
    }

    @Override
    public void bankAccountLinked(BankAccount account) {
        outbox.publish(EventType.BANK_ACCOUNT_LINKED, account.id().toString(),
                new UserEvents.BankAccountLinked(account.userId(), account.id(), account.maskedNumber(), account.ifsc()));
    }
}
