package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.transaction.client.WalletClient;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.entity.TransactionStatusHistory;
import com.payverse.transaction.entity.TransferSource;
import com.payverse.transaction.exception.TransactionErrorCode;
import com.payverse.transaction.repository.MoneyRequestRepository;
import com.payverse.transaction.repository.TransactionRepository;
import com.payverse.transaction.repository.TransactionStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/**
 * Orchestrated P2P saga. Each step is one idempotent wallet-service call followed by a <b>committed</b>
 * state update, so a crash at any point leaves a resumable record:
 * <pre>
 * INITIATED ─risk─► PENDING/NOT_STARTED ─debit payer─► DEBITED ─credit payee─► CREDITED (SUCCESS)
 *                                                          │
 *                                                credit refused (business)
 *                                                          ▼
 *                                                   COMPENSATING ─reverse debit─► COMPENSATED (FAILED)
 * </pre>
 * Business refusals (4xx) move the saga forward/back; infrastructure failures (5xx, timeouts, 409/429
 * busy) schedule a retry with back-off, picked up by {@link SagaRecoveryJob}.
 */
@Component
public class TransferSaga {

    private static final Logger log = LoggerFactory.getLogger(TransferSaga.class);
    private static final int MAX_STEPS_PER_RUN = 6;

    private final TransactionRepository transactions;
    private final TransactionStatusHistoryRepository history;
    private final MoneyRequestRepository moneyRequests;
    private final WalletClient wallet;
    private final RiskAssessor risk;
    private final TransactionEventPublisher events;
    private final SagaLock sagaLock;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TransferSaga(TransactionRepository transactions, TransactionStatusHistoryRepository history,
                        MoneyRequestRepository moneyRequests, WalletClient wallet, RiskAssessor risk,
                        TransactionEventPublisher events, SagaLock sagaLock, PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = transactions;
        this.history = history;
        this.moneyRequests = moneyRequests;
        this.wallet = wallet;
        this.risk = risk;
        this.events = events;
        this.sagaLock = sagaLock;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Advances the saga as far as it can go now. Safe to call concurrently and repeatedly. */
    public void run(UUID transactionId) {
        var token = sagaLock.tryLock(transactionId);
        if (token.isEmpty()) {
            return; // another runner is on it
        }
        try {
            for (int i = 0; i < MAX_STEPS_PER_RUN; i++) {
                Transaction t = transactions.findById(transactionId).orElseThrow();
                if (!step(t)) {
                    return;
                }
            }
        } finally {
            sagaLock.unlock(transactionId, token.get());
        }
    }

    /** @return true if the saga progressed and should continue immediately */
    private boolean step(Transaction t) {
        if (t.getStatus() == TransactionStatus.INITIATED) {
            RiskAssessor.Assessment assessment = risk.assess(t);
            if (assessment.decision() == RiskAssessor.Decision.BLOCK) {
                update(t.getId(), txn -> {
                    txn.recordRisk(assessment.score(), assessment.decision().name());
                    return txn.fail(TransactionErrorCode.TXN_RISK_BLOCKED.code(), String.join("; ", assessment.reasons()), now());
                });
                return false;
            }
            update(t.getId(), txn -> {
                txn.recordRisk(assessment.score(), assessment.decision().name());
                return txn.markPending(now());
            });
            return true;
        }
        if (t.getStatus() != TransactionStatus.PENDING) {
            return false;
        }
        return switch (t.getSagaStep()) {
            case NOT_STARTED -> debit(t);
            case DEBITED -> credit(t);
            case COMPENSATING -> compensate(t);
            case CREDITED, COMPENSATED -> false;
        };
    }

    private boolean debit(Transaction t) {
        try {
            WalletClient.Posting posting = wallet.debit(t.getPayerUserId(), t.getAmountMoney(), t.getId(), describe(t, "to " + t.getPayeeRef()));
            update(t.getId(), txn -> {
                txn.recordDebit(posting.journalId(), now());
                return null;
            });
            return true;
        } catch (PayverseException e) {
            if (retryable(e)) {
                return retryLater(t, "debit: " + e.getMessage());
            }
            update(t.getId(), txn -> txn.fail(e.errorCode().code(), e.getMessage(), now()));
            return false;
        }
    }

    private boolean credit(Transaction t) {
        try {
            WalletClient.Posting posting = wallet.credit(t.getPayeeUserId(), t.getAmountMoney(), t.getId(),
                    describe(t, "from " + (t.getPayerRef() == null ? "PayVerse user" : t.getPayerRef())));
            update(t.getId(), txn -> txn.recordCredit(posting.journalId(), now()));
            return false;
        } catch (PayverseException e) {
            if (retryable(e)) {
                return retryLater(t, "credit: " + e.getMessage());
            }
            log.info("Payee credit refused for {} ({}); compensating", t.getId(), e.errorCode().code());
            update(t.getId(), txn -> {
                txn.startCompensation(e.errorCode().code(), e.getMessage(), now());
                return null;
            });
            return true;
        }
    }

    private boolean compensate(Transaction t) {
        try {
            WalletClient.Posting posting = wallet.reverse(t.getPayerUserId(), t.getDebitJournalId(), t.getId(),
                    "Transfer failed: " + t.getFailureCode());
            update(t.getId(), txn -> txn.recordCompensation(posting.journalId(), now()));
        } catch (PayverseException e) {
            // compensation must eventually succeed: keep retrying, loudly
            log.error("Compensation of {} failed ({}); will retry", t.getId(), e.errorCode().code());
            retryLater(t, "compensation: " + e.getMessage());
        }
        return false;
    }

    private boolean retryLater(Transaction t, String reason) {
        update(t.getId(), txn -> {
            txn.scheduleRetry(reason, now());
            if (txn.getAttempts() >= 10) {
                log.error("Saga {} still unfinished after {} attempts (step {})", txn.getId(), txn.getAttempts(), txn.getSagaStep());
            }
            return null;
        });
        return false;
    }

    /**
     * Applies a mutation to a freshly loaded transaction in its own DB transaction (optimistic lock via
     * {@code @Version}), records the status change and publishes the matching event.
     */
    private void update(UUID transactionId, Function<Transaction, Transaction.StatusChange> mutation) {
        tx.executeWithoutResult(status -> {
            Transaction txn = transactions.findById(transactionId).orElseThrow();
            Transaction.StatusChange change = mutation.apply(txn);
            transactions.saveAndFlush(txn);
            if (change == null) {
                return;
            }
            history.save(TransactionStatusHistory.of(change));
            switch (change.to()) {
                case SUCCESS -> {
                    events.completed(txn);
                    if (txn.getSource() == TransferSource.MONEY_REQUEST && txn.getSourceId() != null) {
                        moneyRequests.findById(txn.getSourceId()).ifPresent(r -> r.markPaid(txn.getId(), now()));
                    }
                }
                case FAILED -> events.failed(txn);
                default -> { }
            }
        });
    }

    /** 5xx, timeouts and "busy" answers may succeed later; other 4xx are final business refusals. */
    static boolean retryable(PayverseException e) {
        int status = e.errorCode().httpStatus();
        return status >= 500 || status == 409 || status == 429 || status == 408;
    }

    private static String describe(Transaction t, String counterparty) {
        return t.getNote() == null ? "P2P " + counterparty : t.getNote() + " (" + counterparty + ")";
    }

    private Instant now() {
        return clock.instant();
    }
}
