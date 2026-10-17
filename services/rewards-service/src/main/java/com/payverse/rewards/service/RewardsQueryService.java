package com.payverse.rewards.service;

import com.payverse.common.money.Money;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardStatus;
import com.payverse.rewards.repository.RewardRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** A user's rewards history and totals. */
@Service
public class RewardsQueryService {

    public record Totals(Money credited, Money pending) {
    }

    private final RewardRepository rewards;

    public RewardsQueryService(RewardRepository rewards) {
        this.rewards = rewards;
    }

    @Transactional(readOnly = true)
    public Page<Reward> history(UUID userId, int page, int size) {
        return rewards.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Totals totals(UUID userId) {
        return new Totals(Money.inr(rewards.sumByUserAndStatus(userId, RewardStatus.CREDITED)),
                Money.inr(rewards.sumByUserAndStatus(userId, RewardStatus.PENDING_CREDIT)));
    }
}
