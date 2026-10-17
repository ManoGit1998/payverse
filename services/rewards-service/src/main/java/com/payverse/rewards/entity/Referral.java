package com.payverse.rewards.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Referrer invited referee; both are rewarded once the referee completes minimum KYC. */
@Entity
@Table(name = "referrals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Referral {

    @Id
    private UUID id;

    @Column(name = "referrer_id", nullable = false)
    private UUID referrerId;

    @Column(name = "referee_id", nullable = false)
    private UUID refereeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferralStatus status;

    @Column(name = "referrer_reward_id")
    private UUID referrerRewardId;

    @Column(name = "referee_reward_id")
    private UUID refereeRewardId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "rewarded_at")
    private Instant rewardedAt;

    @Version
    private long version;

    public static Referral pending(UUID referrerId, UUID refereeId, Instant now) {
        if (referrerId.equals(refereeId)) {
            throw new IllegalArgumentException("Users cannot refer themselves");
        }
        Referral referral = new Referral();
        referral.id = UUID.randomUUID();
        referral.referrerId = referrerId;
        referral.refereeId = refereeId;
        referral.status = ReferralStatus.PENDING;
        referral.createdAt = now;
        return referral;
    }

    public boolean isPending() {
        return status == ReferralStatus.PENDING;
    }

    public void reward(UUID referrerReward, UUID refereeReward, Instant now) {
        if (!isPending()) {
            throw new IllegalStateException("Referral " + id + " was already rewarded");
        }
        status = ReferralStatus.REWARDED;
        referrerRewardId = referrerReward;
        refereeRewardId = refereeReward;
        rewardedAt = now;
    }
}
