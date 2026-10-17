package com.payverse.rewards.service;

import com.payverse.common.error.PayverseException;
import com.payverse.rewards.client.WalletClient;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.ScratchCard;
import com.payverse.rewards.entity.ScratchCardStatus;
import com.payverse.rewards.repository.CashbackRuleRepository;
import com.payverse.rewards.repository.RewardRepository;
import com.payverse.rewards.repository.ScratchCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Takes rewards back when their source transaction is reversed: unscratched cards are voided,
 * uncredited rewards are voided, and credited cashback is reversed on the wallet
 * ({@code reward:<id>:clawback}, idempotent). If the wallet no longer holds the money the reward is
 * marked CLAWBACK_FAILED for reconciliation. Budgets are given back.
 */
@Service
public class ClawbackService {

    public record Result(int voidedCards, int voidedRewards, int clawedBack, int failed) {
    }

    private static final Logger log = LoggerFactory.getLogger(ClawbackService.class);

    private final ScratchCardRepository cards;
    private final RewardRepository rewards;
    private final CashbackRuleRepository rules;
    private final WalletClient wallet;

    public ClawbackService(ScratchCardRepository cards, RewardRepository rewards, CashbackRuleRepository rules, WalletClient wallet) {
        this.cards = cards;
        this.rewards = rewards;
        this.rules = rules;
        this.wallet = wallet;
    }

    /**
     * Joins the consumer's transaction. The wallet reversal is a remote call inside it: if the
     * commit fails, the redelivered event repeats the reversal and the wallet replays it.
     */
    @Transactional
    public Result onTransactionReversed(UUID originalTransactionId, String reason) {
        String source = originalTransactionId.toString();
        int voidedCards = 0;
        List<String> rewardSources = new ArrayList<>(List.of(source));
        for (ScratchCard card : cards.findBySourceId(source)) {
            if (card.getStatus() == ScratchCardStatus.UNSCRATCHED) {
                card.voidCard();
                rules.releaseBudget(card.getRuleCode(), card.getAmount());
                voidedCards++;
            } else if (card.getStatus() == ScratchCardStatus.SCRATCHED) {
                rewardSources.add(card.getId().toString());
            }
        }
        int voided = 0;
        int clawed = 0;
        int failed = 0;
        for (Reward reward : rewards.findBySourceIdIn(rewardSources)) {
            switch (reward.getStatus()) {
                case PENDING_CREDIT -> {
                    reward.voidPending();
                    rules.releaseBudget(reward.getRuleCode(), reward.getAmount());
                    voided++;
                }
                case CREDITED -> {
                    if (clawBack(reward, reason)) {
                        clawed++;
                    } else {
                        failed++;
                    }
                }
                default -> {
                    // FAILED / VOIDED / already clawed back: nothing to take back
                }
            }
        }
        return new Result(voidedCards, voided, clawed, failed);
    }

    private boolean clawBack(Reward reward, String reason) {
        try {
            wallet.reverse(reward.getUserId(), reward.getJournalId(), reward.creditKey() + ":clawback",
                    "Cashback clawback: " + (reason == null ? "transaction reversed" : reason));
            reward.markClawedBack();
            rules.releaseBudget(reward.getRuleCode(), reward.getAmount());
            return true;
        } catch (PayverseException e) {
            if (e.errorCode().httpStatus() >= 500) {
                throw e; // infrastructure: let the consumer retry
            }
            log.warn("Clawback of reward {} failed: {}", reward.getId(), e.errorCode().code());
            reward.markClawbackFailed(e.errorCode().code());
            return false;
        }
    }
}
