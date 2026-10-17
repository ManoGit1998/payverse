package com.payverse.transaction.service;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.client.RemoteErrorCode;
import com.payverse.transaction.client.WalletClient;
import com.payverse.transaction.entity.PayeeType;
import com.payverse.transaction.entity.SagaStep;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.entity.TransferSource;
import com.payverse.transaction.repository.MoneyRequestRepository;
import com.payverse.transaction.repository.TransactionRepository;
import com.payverse.transaction.repository.TransactionStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferSagaTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);

    final Map<UUID, Transaction> store = new HashMap<>();
    final TransactionRepository transactions = mock(TransactionRepository.class);
    final WalletClient wallet = mock(WalletClient.class);
    final TransactionEventPublisher events = mock(TransactionEventPublisher.class);
    final SagaLock lock = mock(SagaLock.class);
    RiskAssessor risk = t -> new RiskAssessor.Assessment(RiskAssessor.Decision.ALLOW, 0, List.of());
    Transaction txn;

    @BeforeEach
    void setUp() {
        when(transactions.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(transactions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lock.tryLock(any())).thenReturn(Optional.of("token"));
        txn = Transaction.initiate(UUID.randomUUID(), "Asha", new Transaction.Payee(UUID.randomUUID(), PayeeType.VPA, "ravi@payverse", "Ravi"),
                Money.inr("250"), null, TransferSource.DIRECT, null, "key-00000001", "h", CLOCK.instant());
        store.put(txn.getId(), txn);
    }

    TransferSaga saga() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TransferSaga(transactions, mock(TransactionStatusHistoryRepository.class), mock(MoneyRequestRepository.class),
                wallet, risk, events, lock, tm, CLOCK);
    }

    static WalletClient.Posting posting() {
        return new WalletClient.Posting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);
    }

    static PayverseException remote(String code, int status) {
        return new PayverseException(new RemoteErrorCode(code, status, code), code);
    }

    @Test
    void debitThenCreditSucceeds() {
        when(wallet.debit(eq(txn.getPayerUserId()), eq(Money.inr("250")), eq(txn.getId()), anyString())).thenReturn(posting());
        when(wallet.credit(eq(txn.getPayeeUserId()), eq(Money.inr("250")), eq(txn.getId()), anyString())).thenReturn(posting());
        saga().run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(txn.getSagaStep()).isEqualTo(SagaStep.CREDITED);
        verify(events).completed(txn);
        verify(lock).unlock(txn.getId(), "token");
    }

    @Test
    void refusedDebitFailsWithoutTouchingThePayee() {
        when(wallet.debit(any(), any(), any(), anyString())).thenThrow(remote("WALLET_INSUFFICIENT_BALANCE", 422));
        saga().run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(txn.getFailureCode()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
        verify(wallet, never()).credit(any(), any(), any(), anyString());
        verify(events).failed(txn);
    }

    @Test
    void refusedCreditIsCompensated() {
        WalletClient.Posting debit = posting();
        when(wallet.debit(any(), any(), any(), anyString())).thenReturn(debit);
        when(wallet.credit(any(), any(), any(), anyString())).thenThrow(remote("WALLET_FROZEN", 403));
        when(wallet.reverse(eq(txn.getPayerUserId()), eq(debit.journalId()), eq(txn.getId()), anyString())).thenReturn(posting());
        saga().run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(txn.getSagaStep()).isEqualTo(SagaStep.COMPENSATED);
        assertThat(txn.getFailureCode()).isEqualTo("WALLET_FROZEN");
        assertThat(txn.getCompensationJournalId()).isNotNull();
    }

    @Test
    void outagesScheduleARetryAndTheNextRunFinishes() {
        when(wallet.debit(any(), any(), any(), anyString())).thenReturn(posting());
        when(wallet.credit(any(), any(), any(), anyString()))
                .thenThrow(new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, "wallet down"))
                .thenReturn(posting());
        TransferSaga saga = saga();
        saga.run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(txn.getSagaStep()).isEqualTo(SagaStep.DEBITED);
        assertThat(txn.getNextAttemptAt()).isNotNull();

        saga.run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(wallet, org.mockito.Mockito.times(1)).debit(any(), any(), any(), anyString());
    }

    @Test
    void busyWalletIsRetriedNotFailed() {
        when(wallet.debit(any(), any(), any(), anyString())).thenThrow(remote("WALLET_LOCK_TIMEOUT", 409));
        saga().run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(TransferSaga.retryable(remote("WALLET_LIMIT_EXCEEDED", 422))).isFalse();
        assertThat(TransferSaga.retryable(remote("X", 503))).isTrue();
    }

    @Test
    void riskBlockFailsBeforeAnyMoneyMoves() {
        risk = t -> new RiskAssessor.Assessment(RiskAssessor.Decision.BLOCK, 95, List.of("velocity"));
        saga().run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(txn.getFailureCode()).isEqualTo("TXN_RISK_BLOCKED");
        verify(wallet, never()).debit(any(), any(), any(), anyString());
    }

    @Test
    void anotherRunnerHoldingTheLockMeansSkip() {
        when(lock.tryLock(any())).thenReturn(Optional.empty());
        saga().run(txn.getId());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.INITIATED);
        assertThat(new PermissiveRiskAssessor().assess(txn).decision()).isEqualTo(RiskAssessor.Decision.ALLOW);
    }
}
