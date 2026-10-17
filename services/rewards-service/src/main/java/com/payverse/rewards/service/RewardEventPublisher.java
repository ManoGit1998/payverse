package com.payverse.rewards.service;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.RewardsEvents;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.ScratchCard;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** rewards-service events via the transactional outbox (call inside the business transaction). */
@Service
public class RewardEventPublisher {

    private final OutboxPublisher outbox;

    public RewardEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    /** A fact: the cashback is already in the wallet. */
    public void cashbackGranted(Reward reward, UUID sourceTransactionId) {
        outbox.publish(EventType.CASHBACK_GRANTED, reward.getId().toString(), new RewardsEvents.CashbackGranted(
                reward.getId(), reward.getUserId(), Amount.of(reward.getMoney()), sourceTransactionId, reward.getRuleCode()));
    }

    public void scratchCardIssued(ScratchCard card) {
        outbox.publish(EventType.SCRATCH_CARD_ISSUED, card.getId().toString(), new RewardsEvents.ScratchCardIssued(
                card.getId(), card.getUserId(), card.getSourceType().name(), card.getSourceId(), card.getRuleCode(), card.getExpiresAt()));
    }

    /** The transaction a reward came from, if it came from one. */
    static UUID sourceTransaction(RewardSource type, String sourceId) {
        if (type != RewardSource.TRANSACTION && type != RewardSource.BILL_PAYMENT) {
            return null;
        }
        try {
            return UUID.fromString(sourceId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
