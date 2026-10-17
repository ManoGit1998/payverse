package com.payverse.rewards.entity;

import com.payverse.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewardEntitiesTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final UUID USER = UUID.randomUUID();

    @Test
    void rewardCreditLifecycle() {
        Reward r = Reward.pending(USER, "R", RewardSource.TRANSACTION, "t1", Money.inr("10"), NOW);
        assertThat(r.creditKey()).isEqualTo("reward:" + r.getId());
        assertThat(r.isDue(NOW)).isTrue();
        assertThat(r.recordCreditFailure("e1", 3, NOW)).isFalse();
        assertThat(r.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(r.isDue(NOW)).isFalse();
        UUID journal = UUID.randomUUID();
        r.markCredited(journal, NOW);
        assertThat(r.getStatus()).isEqualTo(RewardStatus.CREDITED);
        assertThat(r.getJournalId()).isEqualTo(journal);
        assertThat(r.getAttempts()).isEqualTo(2);
        assertThat(r.getCreditedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> r.markCredited(journal, NOW)).isInstanceOf(IllegalStateException.class);
        r.markClawedBack();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.CLAWED_BACK);
        assertThat(r.getMoney()).isEqualTo(Money.inr("10"));
    }

    @Test
    void rewardFailureVoidAndClawbackFailure() {
        Reward r = Reward.pending(USER, "R", RewardSource.COUPON, "c", Money.inr("10"), NOW);
        r.recordCreditFailure("x".repeat(400), 2, NOW);
        assertThat(r.getLastError()).hasSize(300);
        assertThat(r.recordCreditFailure(null, 2, NOW)).isTrue();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.FAILED);

        Reward v = Reward.pending(USER, "R", RewardSource.COUPON, "c", Money.inr("10"), NOW);
        v.voidPending();
        assertThat(v.getStatus()).isEqualTo(RewardStatus.VOIDED);

        Reward c = Reward.pending(USER, "R", RewardSource.COUPON, "c", Money.inr("10"), NOW);
        c.markCredited(UUID.randomUUID(), NOW);
        c.markClawbackFailed("WALLET_INSUFFICIENT_BALANCE");
        assertThat(c.getStatus()).isEqualTo(RewardStatus.CLAWBACK_FAILED);
        assertThatThrownBy(() -> Reward.pending(USER, "R", RewardSource.COUPON, "c", Money.inr("0"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scratchCardHidesAmountUntilScratched() {
        ScratchCard card = ScratchCard.issue(USER, "P2P", RewardSource.TRANSACTION, "t", Money.inr("7"), Duration.ofDays(30), NOW);
        assertThat(card.revealedAmount()).isEmpty();
        assertThat(card.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        UUID reward = UUID.randomUUID();
        card.scratch(reward, NOW);
        assertThat(card.revealedAmount()).contains(Money.inr("7"));
        assertThat(card.getRewardId()).isEqualTo(reward);
        assertThatThrownBy(() -> card.scratch(reward, NOW)).isInstanceOf(IllegalStateException.class);
        card.expire();
        card.voidCard();
        assertThat(card.getStatus()).isEqualTo(ScratchCardStatus.SCRATCHED);

        ScratchCard old = ScratchCard.issue(USER, "P2P", RewardSource.TRANSACTION, "t", Money.inr("7"), Duration.ofDays(1), NOW);
        assertThatThrownBy(() -> old.scratch(reward, NOW.plus(Duration.ofDays(1)))).hasMessageContaining("expired");
        old.expire();
        assertThat(old.getStatus()).isEqualTo(ScratchCardStatus.EXPIRED);

        ScratchCard voided = ScratchCard.issue(USER, "P2P", RewardSource.TRANSACTION, "t", Money.inr("7"), Duration.ofDays(1), NOW);
        voided.voidCard();
        assertThat(voided.getStatus()).isEqualTo(ScratchCardStatus.VOIDED);
    }

    @Test
    void couponWindowAndLimit() {
        Coupon coupon = Coupon.create(" save10 ", "d", Money.inr("10"), NOW, NOW.plusSeconds(100), 1, NOW);
        assertThat(coupon.getCode()).isEqualTo("SAVE10");
        assertThat(coupon.problemAt(NOW.minusSeconds(1))).isEqualTo(Coupon.Problem.NOT_STARTED);
        assertThat(coupon.problemAt(NOW.plusSeconds(100))).isEqualTo(Coupon.Problem.EXPIRED);
        coupon.redeem(NOW);
        assertThat(coupon.problemAt(NOW)).isEqualTo(Coupon.Problem.EXHAUSTED);
        assertThatThrownBy(() -> coupon.redeem(NOW)).isInstanceOf(IllegalStateException.class);
        coupon.setActive(false);
        assertThat(coupon.problemAt(NOW)).isEqualTo(Coupon.Problem.INACTIVE);
        assertThat(coupon.getCashbackMoney()).isEqualTo(Money.inr("10"));
        assertThat(Coupon.normalize(null)).isEmpty();
        assertThatThrownBy(() -> Coupon.create("a-b", "d", Money.inr("10"), NOW, NOW.plusSeconds(1), 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.create("ABCD", "d", Money.inr("10"), NOW, NOW, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CouponRedemption.of(coupon.getId(), USER, UUID.randomUUID(), NOW).getUserId()).isEqualTo(USER);
    }

    @Test
    void referralCodesAndReferrals() {
        ReferralCode code = ReferralCode.generate(USER, new Random(7), NOW);
        assertThat(code.getCode()).hasSize(8).matches("[" + ReferralCode.ALPHABET + "]+");
        assertThat(ReferralCode.normalize(" ab ")).isEqualTo("AB");
        assertThat(ReferralCode.normalize(null)).isEmpty();

        UUID referee = UUID.randomUUID();
        Referral referral = Referral.pending(USER, referee, NOW);
        assertThat(referral.isPending()).isTrue();
        referral.reward(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(referral.getStatus()).isEqualTo(ReferralStatus.REWARDED);
        assertThat(referral.getRewardedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> referral.reward(null, null, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Referral.pending(USER, USER, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
