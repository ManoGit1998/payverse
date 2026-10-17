package com.payverse.transaction.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.transaction.exception.TransactionErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionEntityTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    static Transaction txn() {
        return Transaction.initiate(UUID.randomUUID(), "Asha (******3210)",
                new Transaction.Payee(UUID.randomUUID(), PayeeType.VPA, "ravi@payverse", "Ravi"), Money.inr("250"), "Dinner",
                TransferSource.DIRECT, null, "key-00000001", "hash", NOW);
    }

    @ParameterizedTest
    @CsvSource({
            "INITIATED,PENDING,true", "INITIATED,FAILED,true", "INITIATED,SUCCESS,false", "INITIATED,REVERSED,false",
            "PENDING,SUCCESS,true", "PENDING,FAILED,true", "PENDING,INITIATED,false", "PENDING,REVERSED,false",
            "SUCCESS,REVERSED,true", "SUCCESS,FAILED,false", "SUCCESS,PENDING,false",
            "FAILED,PENDING,false", "FAILED,SUCCESS,false", "REVERSED,SUCCESS,false"})
    void stateMachine(TransactionStatus from, TransactionStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    @Test
    void settledStates() {
        assertThat(TransactionStatus.SUCCESS.isSettled()).isTrue();
        assertThat(TransactionStatus.FAILED.isSettled()).isTrue();
        assertThat(TransactionStatus.PENDING.isSettled()).isFalse();
    }

    @Test
    void happyPathThroughTheSaga() {
        Transaction t = txn();
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.INITIATED);
        assertThat(t.markPending(NOW).to()).isEqualTo(TransactionStatus.PENDING);
        UUID debit = UUID.randomUUID();
        t.recordDebit(debit, NOW);
        assertThat(t.getSagaStep()).isEqualTo(SagaStep.DEBITED);
        Transaction.StatusChange done = t.recordCredit(UUID.randomUUID(), NOW.plusSeconds(1));
        assertThat(done.from()).isEqualTo(TransactionStatus.PENDING);
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(t.getCompletedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(t.getDebitJournalId()).isEqualTo(debit);
    }

    @Test
    void compensationPath() {
        Transaction t = txn();
        t.markPending(NOW);
        t.recordDebit(UUID.randomUUID(), NOW);
        t.startCompensation("WALLET_LIMIT_EXCEEDED", "payee at max balance", NOW);
        assertThat(t.getSagaStep()).isEqualTo(SagaStep.COMPENSATING);
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.PENDING);
        t.recordCompensation(UUID.randomUUID(), NOW);
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(t.getSagaStep()).isEqualTo(SagaStep.COMPENSATED);
        assertThat(t.getFailureCode()).isEqualTo("WALLET_LIMIT_EXCEEDED");
    }

    @Test
    void illegalMovesAreRejected() {
        Transaction t = txn();
        assertThatThrownBy(() -> t.recordCredit(UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(TransactionErrorCode.TXN_ILLEGAL_STATE));
        assertThatThrownBy(() -> t.transitionTo(TransactionStatus.SUCCESS, "x", NOW))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(TransactionErrorCode.TXN_ILLEGAL_STATE));
        t.markPending(NOW);
        t.recordDebit(UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> t.fail("X", "money moved", NOW)).isInstanceOf(PayverseException.class);
    }

    @Test
    void retriesBackOffExponentiallyUpToFiveMinutes() {
        Transaction t = txn();
        t.scheduleRetry("wallet down", NOW);
        assertThat(t.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        for (int i = 0; i < 20; i++) {
            t.scheduleRetry("wallet down", NOW);
        }
        assertThat(t.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(256)));
        assertThat(t.getAttempts()).isEqualTo(21);
    }

    @Test
    void validation() {
        UUID self = UUID.randomUUID();
        assertThatThrownBy(() -> Transaction.initiate(self, null, new Transaction.Payee(self, PayeeType.USER, "me", null), Money.inr("1"),
                null, TransferSource.DIRECT, null, "k", "h", NOW))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(TransactionErrorCode.TXN_SELF_TRANSFER));
        assertThatThrownBy(() -> Transaction.initiate(self, null, new Transaction.Payee(UUID.randomUUID(), PayeeType.USER, "x", null),
                Money.zeroInr(), null, TransferSource.DIRECT, null, "k", "h", NOW))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(TransactionErrorCode.TXN_INVALID_AMOUNT));
        assertThat(txn().involves(UUID.randomUUID())).isFalse();
        assertThat(TransactionStatusHistory.created(txn()).getToStatus()).isEqualTo(TransactionStatus.INITIATED);
    }

    @Test
    void moneyRequestLifecycle() {
        UUID requester = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        MoneyRequest request = MoneyRequest.create(requester, "Asha", payer, "******1111", Money.inr("100"), "Rent", null, NOW);
        assertThat(request.isPayable(NOW)).isTrue();
        request.markPaid(UUID.randomUUID(), NOW);
        assertThat(request.getStatus()).isEqualTo(MoneyRequestStatus.PAID);
        assertThatThrownBy(() -> request.decline(NOW)).isInstanceOf(PayverseException.class);

        MoneyRequest expired = MoneyRequest.create(requester, "Asha", payer, "x", Money.inr("1"), null, null, NOW);
        assertThat(expired.isPayable(NOW.plus(MoneyRequest.TTL))).isFalse();
        assertThat(expired.getStatus()).isEqualTo(MoneyRequestStatus.EXPIRED);
        expired.markPaid(UUID.randomUUID(), NOW); // a transfer that landed just after expiry still counts
        assertThat(expired.getStatus()).isEqualTo(MoneyRequestStatus.PAID);

        MoneyRequest cancelled = MoneyRequest.create(requester, "Asha", payer, "x", Money.inr("1"), null, null, NOW);
        cancelled.cancel(NOW);
        assertThat(cancelled.getStatus()).isEqualTo(MoneyRequestStatus.CANCELLED);
        assertThatThrownBy(() -> MoneyRequest.create(requester, "a", requester, "a", Money.inr("1"), null, null, NOW))
                .isInstanceOf(PayverseException.class);
        assertThat(SplitBill.create(requester, Money.inr("90"), Money.inr("30"), null, NOW).getCreatorShareMoney()).isEqualTo(Money.inr("30"));
    }
}
