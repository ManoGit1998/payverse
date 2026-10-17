package com.payverse.rewards.service;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.error.ErrorCode;
import com.payverse.common.money.Money;
import com.payverse.rewards.client.WalletClient;
import com.payverse.rewards.config.RewardsProperties;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.RewardStatus;
import com.payverse.rewards.entity.ScratchCard;
import com.payverse.rewards.entity.ScratchCardStatus;
import com.payverse.rewards.repository.CashbackRuleRepository;
import com.payverse.rewards.repository.RewardRepository;
import com.payverse.rewards.repository.ScratchCardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RewardCreditAndClawbackTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final RewardRepository rewards = mock(RewardRepository.class);
    final ScratchCardRepository cards = mock(ScratchCardRepository.class);
    final CashbackRuleRepository rules = mock(CashbackRuleRepository.class);
    final WalletClient wallet = mock(WalletClient.class);
    final RewardEventPublisher events = mock(RewardEventPublisher.class);
    final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    final UUID user = UUID.randomUUID();

    RewardCreditService creditService() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new RewardCreditService(rewards, wallet, events, new RewardsProperties(null, null, null, null, 2, null), tm, clock);
    }

    Reward pending(String source) {
        Reward r = Reward.pending(user, "R", RewardSource.TRANSACTION, source, Money.inr("10"), NOW);
        when(rewards.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void creditsDueRewardsAndPublishes() {
        String txn = UUID.randomUUID().toString();
        Reward r = pending(txn);
        UUID journal = UUID.randomUUID();
        when(rewards.findDue(eq(NOW), any(Pageable.class))).thenReturn(List.of(r.getId(), UUID.randomUUID()));
        when(wallet.creditCashback(user, Money.inr("10"), r.creditKey(), r.getId(), "Cashback R"))
                .thenReturn(new WalletClient.Posting(journal, UUID.randomUUID(), user, false));

        assertThat(creditService().creditDue()).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(RewardStatus.CREDITED);
        assertThat(r.getJournalId()).isEqualTo(journal);
        verify(events).cashbackGranted(r, UUID.fromString(txn));
        assertThat(creditService().credit(r.getId())).isFalse(); // no longer due
    }

    @Test
    void failuresBackOffThenFail() {
        Reward r = pending("x");
        when(wallet.creditCashback(any(), any(), anyString(), any(), anyString()))
                .thenThrow(new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, "down"));
        RewardCreditService service = creditService();
        assertThat(service.credit(r.getId())).isFalse();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.PENDING_CREDIT);
        assertThat(r.getLastError()).startsWith("DOWNSTREAM_UNAVAILABLE");

        RewardCreditService later = new RewardCreditService(rewards, wallet, events, new RewardsProperties(null, null, null, null, 2, null), tm,
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        assertThat(later.credit(r.getId())).isFalse();
        assertThat(r.getStatus()).isEqualTo(RewardStatus.FAILED);
        verify(events, never()).cashbackGranted(any(), any());
    }

    @Test
    void concurrentCompletionIsIgnored() {
        Reward r = pending("x");
        when(wallet.creditCashback(any(), any(), anyString(), any(), anyString())).thenThrow(new OptimisticLockingFailureException("race"));
        assertThat(creditService().credit(r.getId())).isFalse();
        assertThat(r.getAttempts()).isZero();
        doThrow(new IllegalStateException("odd")).when(wallet).creditCashback(any(), any(), anyString(), any(), anyString());
        creditService().credit(r.getId());
        assertThat(r.getLastError()).isEqualTo("IllegalStateException");
    }

    @Test
    void sourceTransactionOnlyForTransactionsAndBills() {
        UUID id = UUID.randomUUID();
        assertThat(RewardEventPublisher.sourceTransaction(RewardSource.TRANSACTION, id.toString())).isEqualTo(id);
        assertThat(RewardEventPublisher.sourceTransaction(RewardSource.BILL_PAYMENT, "not-a-uuid")).isNull();
        assertThat(RewardEventPublisher.sourceTransaction(RewardSource.COUPON, id.toString())).isNull();
    }

    @Test
    void clawbackVoidsReversesOrFlagsRewards() {
        UUID txn = UUID.randomUUID();
        String source = txn.toString();
        ScratchCard unscratched = ScratchCard.issue(user, "SC", RewardSource.TRANSACTION, source, Money.inr("5"), Duration.ofDays(1), NOW);
        ScratchCard scratched = ScratchCard.issue(user, "SC", RewardSource.TRANSACTION, source, Money.inr("7"), Duration.ofDays(1), NOW);
        Reward cardReward = Reward.pending(user, "SC", RewardSource.SCRATCH_CARD, scratched.getId().toString(), Money.inr("7"), NOW);
        scratched.scratch(cardReward.getId(), NOW);
        cardReward.markCredited(UUID.randomUUID(), NOW);
        Reward pendingFlat = Reward.pending(user, "FLAT", RewardSource.TRANSACTION, source, Money.inr("25"), NOW);
        Reward brokeUser = Reward.pending(user, "PCT", RewardSource.TRANSACTION, source, Money.inr("3"), NOW);
        brokeUser.markCredited(UUID.randomUUID(), NOW);
        Reward failed = Reward.pending(user, "X", RewardSource.TRANSACTION, source, Money.inr("3"), NOW);
        failed.recordCreditFailure("e", 1, NOW);

        when(cards.findBySourceId(source)).thenReturn(List.of(unscratched, scratched));
        when(rewards.findBySourceIdIn(anyList())).thenReturn(List.of(cardReward, pendingFlat, brokeUser, failed));
        when(wallet.reverse(eq(user), eq(brokeUser.getJournalId()), anyString(), anyString()))
                .thenThrow(new PayverseException(code("WALLET_INSUFFICIENT_BALANCE", 422), null));

        ClawbackService.Result result = new ClawbackService(cards, rewards, rules, wallet).onTransactionReversed(txn, null);

        assertThat(result).isEqualTo(new ClawbackService.Result(1, 1, 1, 1));
        assertThat(unscratched.getStatus()).isEqualTo(ScratchCardStatus.VOIDED);
        assertThat(cardReward.getStatus()).isEqualTo(RewardStatus.CLAWED_BACK);
        assertThat(pendingFlat.getStatus()).isEqualTo(RewardStatus.VOIDED);
        assertThat(brokeUser.getStatus()).isEqualTo(RewardStatus.CLAWBACK_FAILED);
        verify(wallet).reverse(user, cardReward.getJournalId(), cardReward.creditKey() + ":clawback", "Cashback clawback: transaction reversed");
        verify(rules).releaseBudget("SC", unscratched.getAmount());
        verify(rules).releaseBudget("FLAT", pendingFlat.getAmount());
    }

    @Test
    void clawbackRethrowsInfrastructureFailures() {
        UUID txn = UUID.randomUUID();
        Reward credited = Reward.pending(user, "R", RewardSource.TRANSACTION, txn.toString(), Money.inr("3"), NOW);
        credited.markCredited(UUID.randomUUID(), NOW);
        when(rewards.findBySourceIdIn(anyList())).thenReturn(List.of(credited));
        when(wallet.reverse(any(), any(), anyString(), anyString()))
                .thenThrow(new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, null));
        assertThatThrownBy(() -> new ClawbackService(cards, rewards, rules, wallet).onTransactionReversed(txn, "r"))
                .isInstanceOf(PayverseException.class);
        assertThat(credited.getStatus()).isEqualTo(RewardStatus.CREDITED);
    }

    static ErrorCode code(String name, int status) {
        return new ErrorCode() {
            public String code() { return name; }
            public int httpStatus() { return status; }
            public String title() { return name; }
        };
    }
}
