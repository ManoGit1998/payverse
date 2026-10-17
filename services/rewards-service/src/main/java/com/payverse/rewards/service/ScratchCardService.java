package com.payverse.rewards.service;

import com.payverse.common.error.PayverseException;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.ScratchCard;
import com.payverse.rewards.entity.ScratchCardStatus;
import com.payverse.rewards.exception.RewardsErrorCode;
import com.payverse.rewards.repository.CashbackRuleRepository;
import com.payverse.rewards.repository.RewardRepository;
import com.payverse.rewards.repository.ScratchCardRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Scratching reveals the amount and creates the reward (credited by the credit job). Scratching twice is a no-op. */
@Service
public class ScratchCardService {

    private final ScratchCardRepository cards;
    private final RewardRepository rewards;
    private final CashbackRuleRepository rules;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ScratchCardService(ScratchCardRepository cards, RewardRepository rewards, CashbackRuleRepository rules,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.cards = cards;
        this.rewards = rewards;
        this.rules = rules;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ScratchCard> list(UUID userId, int page, int size) {
        return cards.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    @Transactional
    public ScratchCard scratch(UUID userId, UUID cardId) {
        ScratchCard card = cards.lockById(cardId).filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new PayverseException(RewardsErrorCode.REWARDS_SCRATCH_CARD_NOT_FOUND, null));
        Instant now = clock.instant();
        switch (card.getStatus()) {
            case SCRATCHED -> {
                return card;
            }
            case VOIDED -> throw new PayverseException(RewardsErrorCode.REWARDS_SCRATCH_CARD_VOIDED, null);
            case EXPIRED -> throw new PayverseException(RewardsErrorCode.REWARDS_SCRATCH_CARD_EXPIRED, null);
            case UNSCRATCHED -> {
                if (card.isExpired(now)) {
                    throw new PayverseException(RewardsErrorCode.REWARDS_SCRATCH_CARD_EXPIRED, null);
                }
            }
        }
        Reward reward = rewards.save(Reward.pending(userId, card.getRuleCode(), RewardSource.SCRATCH_CARD, card.getId().toString(),
                card.getMoney(), now));
        card.scratch(reward.getId(), now);
        return card;
    }

    /** Expires unscratched cards past their date and returns their amount to the rule budget. */
    @Scheduled(fixedDelayString = "${payverse.rewards.expiry-interval-ms:60000}")
    public int expireDue() {
        List<UUID> expired = cards.findExpired(clock.instant(), PageRequest.of(0, 200));
        int count = 0;
        for (UUID id : expired) {
            Boolean done = tx.execute(status -> cards.lockById(id)
                    .filter(c -> c.getStatus() == ScratchCardStatus.UNSCRATCHED && c.isExpired(clock.instant()))
                    .map(c -> {
                        c.expire();
                        rules.releaseBudget(c.getRuleCode(), c.getAmount());
                        return true;
                    }).orElse(false));
            if (Boolean.TRUE.equals(done)) {
                count++;
            }
        }
        return count;
    }
}
