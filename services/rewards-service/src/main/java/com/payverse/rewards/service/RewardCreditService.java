package com.payverse.rewards.service;

import com.payverse.common.error.PayverseException;
import com.payverse.rewards.client.WalletClient;
import com.payverse.rewards.config.RewardsProperties;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardStatus;
import com.payverse.rewards.repository.RewardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Credits PENDING_CREDIT rewards to wallets. The wallet call happens outside any database
 * transaction and is idempotent ({@code reward:<id>}), so a crash or a second instance can at
 * worst repeat the call, which the wallet replays. Then the reward is marked CREDITED and
 * {@code cashback-granted} is published in one transaction.
 */
@Service
public class RewardCreditService {

    private static final Logger log = LoggerFactory.getLogger(RewardCreditService.class);

    private final RewardRepository rewards;
    private final WalletClient wallet;
    private final RewardEventPublisher events;
    private final RewardsProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RewardCreditService(RewardRepository rewards, WalletClient wallet, RewardEventPublisher events, RewardsProperties properties,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.rewards = rewards;
        this.wallet = wallet;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payverse.rewards.credit-interval-ms:1000}")
    public int creditDue() {
        List<UUID> due = rewards.findDue(clock.instant(), PageRequest.of(0, properties.creditBatch()));
        int credited = 0;
        for (UUID id : due) {
            if (credit(id)) {
                credited++;
            }
        }
        return credited;
    }

    /** @return true if this call credited the reward */
    public boolean credit(UUID rewardId) {
        Reward reward = rewards.findById(rewardId).orElse(null);
        if (reward == null || !reward.isDue(clock.instant())) {
            return false;
        }
        try {
            WalletClient.Posting posting = wallet.creditCashback(reward.getUserId(), reward.getMoney(), reward.creditKey(), reward.getId(),
                    "Cashback " + reward.getRuleCode());
            return Boolean.TRUE.equals(tx.execute(status -> {
                Reward current = rewards.findById(rewardId).orElseThrow();
                if (current.getStatus() != RewardStatus.PENDING_CREDIT) {
                    return false;
                }
                current.markCredited(posting.journalId(), clock.instant());
                events.cashbackGranted(current, RewardEventPublisher.sourceTransaction(current.getSourceType(), current.getSourceId()));
                return true;
            }));
        } catch (OptimisticLockingFailureException e) {
            return false; // another instance finished it
        } catch (RuntimeException e) {
            String error = e instanceof PayverseException p ? p.errorCode().code() + ": " + p.getMessage() : e.getClass().getSimpleName();
            recordFailure(rewardId, error);
            return false;
        }
    }

    private void recordFailure(UUID rewardId, String error) {
        try {
            tx.executeWithoutResult(status -> rewards.findById(rewardId).ifPresent(r -> {
                if (r.getStatus() != RewardStatus.PENDING_CREDIT) {
                    return;
                }
                if (r.recordCreditFailure(error, properties.maxCreditAttempts(), clock.instant())) {
                    log.error("Reward {} FAILED after {} attempts: {}", rewardId, r.getAttempts(), error);
                } else {
                    log.warn("Reward {} credit failed (attempt {}): {}; retrying at {}", rewardId, r.getAttempts(), error, r.getNextAttemptAt());
                }
            }));
        } catch (OptimisticLockingFailureException ignored) {
            // concurrently updated; the next run sees the new state
        }
    }
}
