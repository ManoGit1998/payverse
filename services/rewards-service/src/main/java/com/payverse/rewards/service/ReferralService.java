package com.payverse.rewards.service;

import com.payverse.common.money.Money;
import com.payverse.rewards.config.RewardsProperties;
import com.payverse.rewards.entity.Referral;
import com.payverse.rewards.entity.ReferralCode;
import com.payverse.rewards.entity.ReferralStatus;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.repository.ReferralCodeRepository;
import com.payverse.rewards.repository.ReferralRepository;
import com.payverse.rewards.repository.RewardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Referrals: every user gets a code on registration; a user who signed up with someone's code
 * becomes their referee. When the referee first reaches MIN_KYC (or FULL_KYC), both get a bonus.
 */
@Service
public class ReferralService {

    public static final String REFERRER_RULE = "REFERRAL_REFERRER";
    public static final String REFEREE_RULE = "REFERRAL_REFEREE";
    private static final Set<String> QUALIFYING_TIERS = Set.of("MIN_KYC", "FULL_KYC");
    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    public record ReferralSummary(String code, long referred, long rewarded, Money referrerBonus, Money refereeBonus) {
    }

    private final ReferralCodeRepository codes;
    private final ReferralRepository referrals;
    private final RewardRepository rewards;
    private final RewardsProperties properties;
    private final RandomGenerator random;
    private final Clock clock;

    public ReferralService(ReferralCodeRepository codes, ReferralRepository referrals, RewardRepository rewards, RewardsProperties properties,
                           RandomGenerator random, Clock clock) {
        this.codes = codes;
        this.referrals = referrals;
        this.rewards = rewards;
        this.properties = properties;
        this.random = random;
        this.clock = clock;
    }

    @Transactional
    public void onUserRegistered(UUID userId, String usedReferralCode) {
        Instant now = clock.instant();
        codeFor(userId, now);
        if (usedReferralCode == null || usedReferralCode.isBlank() || referrals.findByRefereeId(userId).isPresent()) {
            return;
        }
        Optional<ReferralCode> referrer = codes.findByCode(ReferralCode.normalize(usedReferralCode));
        if (referrer.isEmpty() || referrer.get().getUserId().equals(userId)) {
            log.info("Ignoring unknown or own referral code for user {}", userId);
            return;
        }
        referrals.save(Referral.pending(referrer.get().getUserId(), userId, now));
    }

    @Transactional
    public Optional<Referral> onKycUpgraded(UUID userId, String toTier) {
        if (!QUALIFYING_TIERS.contains(toTier)) {
            return Optional.empty();
        }
        return referrals.findByRefereeId(userId).filter(Referral::isPending).map(referral -> {
            Instant now = clock.instant();
            String source = referral.getId().toString();
            Reward referrerReward = rewards.save(Reward.pending(referral.getReferrerId(), REFERRER_RULE, RewardSource.REFERRAL, source,
                    Money.inr(properties.referrerBonus()), now));
            Reward refereeReward = rewards.save(Reward.pending(referral.getRefereeId(), REFEREE_RULE, RewardSource.REFERRAL, source,
                    Money.inr(properties.refereeBonus()), now));
            referral.reward(referrerReward.getId(), refereeReward.getId(), now);
            return referral;
        });
    }

    @Transactional
    public ReferralSummary summary(UUID userId) {
        ReferralCode code = codeFor(userId, clock.instant());
        return new ReferralSummary(code.getCode(), referrals.countByReferrerId(userId),
                referrals.countByReferrerIdAndStatus(userId, ReferralStatus.REWARDED),
                Money.inr(properties.referrerBonus()), Money.inr(properties.refereeBonus()));
    }

    /** Existing code, or a new unique one (users created before rewards-service existed get one lazily). */
    private ReferralCode codeFor(UUID userId, Instant now) {
        return codes.findById(userId).orElseGet(() -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                ReferralCode candidate = ReferralCode.generate(userId, random, now);
                if (!codes.existsByCode(candidate.getCode())) {
                    return codes.save(candidate);
                }
            }
            throw new IllegalStateException("Could not generate a unique referral code");
        });
    }
}
