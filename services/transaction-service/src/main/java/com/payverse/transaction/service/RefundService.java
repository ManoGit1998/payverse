package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.transaction.client.WalletClient;
import com.payverse.transaction.entity.RefundStatus;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatusHistory;
import com.payverse.transaction.exception.TransactionErrorCode;
import com.payverse.transaction.repository.TransactionRepository;
import com.payverse.transaction.repository.TransactionStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Refunds a SUCCESS transfer (approved by admin-service): reverse the payee's credit journal
 * ({@code refund:<id>:payee}), then the payer's debit journal ({@code refund:<id>:payer}), then
 * SUCCESS → REVERSED + {@code transaction.reversed}. Each step is idempotent and committed on its
 * own; calling again with the same refund id resumes or returns the result, so the caller simply
 * retries while the refund is PENDING. If the payee no longer has the money the refund FAILS and
 * nothing moves.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final TransactionRepository transactions;
    private final TransactionStatusHistoryRepository history;
    private final WalletClient wallet;
    private final TransactionEventPublisher events;
    private final SagaLock lock;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(TransactionRepository transactions, TransactionStatusHistoryRepository history, WalletClient wallet,
                         TransactionEventPublisher events, SagaLock lock, PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = transactions;
        this.history = history;
        this.wallet = wallet;
        this.events = events;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Transaction refund(UUID transactionId, UUID refundId, String reason) {
        var token = lock.tryLock(transactionId).orElseThrow(() ->
                new PayverseException(TransactionErrorCode.TXN_NOT_REFUNDABLE, "The transaction is busy; retry shortly"));
        try {
            Transaction t = update(transactionId, txn -> txn.startRefund(refundId, reason, clock.instant()));
            if (t.getRefundStatus() != RefundStatus.PENDING || !refundId.equals(t.getRefundId())) {
                return t;
            }
            if (t.getRefundPayeeJournalId() == null) {
                try {
                    WalletClient.Posting payee = wallet.reverseJournal(t.getPayeeUserId(), t.getCreditJournalId(), "refund:" + refundId + ":payee",
                            "Refund of transfer " + t.getId());
                    t = update(transactionId, txn -> txn.recordPayeeReversal(payee.journalId(), clock.instant()));
                } catch (PayverseException e) {
                    if (TransferSaga.retryable(e)) {
                        log.warn("Refund {} of {} pending: {}", refundId, transactionId, e.getMessage());
                        return t;
                    }
                    log.info("Refund {} of {} failed: {}", refundId, transactionId, e.errorCode().code());
                    return update(transactionId, txn -> txn.failRefund(e.errorCode().code(), clock.instant()));
                }
            }
            try {
                WalletClient.Posting payer = wallet.reverseJournal(t.getPayerUserId(), t.getDebitJournalId(), "refund:" + refundId + ":payer",
                        "Refund of transfer " + t.getId());
                return completed(transactionId, payer.journalId());
            } catch (PayverseException e) {
                // the payee's money is already back in suspense: this step must eventually succeed, so it stays PENDING
                log.warn("Refund {} of {}: payer credit pending: {}", refundId, transactionId, e.getMessage());
                return t;
            }
        } finally {
            lock.unlock(transactionId, token);
        }
    }

    private Transaction completed(UUID transactionId, UUID payerJournal) {
        return tx.execute(status -> {
            Transaction txn = transactions.findById(transactionId).orElseThrow();
            Transaction.StatusChange change = txn.completeRefund(payerJournal, clock.instant());
            transactions.saveAndFlush(txn);
            history.save(TransactionStatusHistory.of(change));
            events.reversed(txn);
            return txn;
        });
    }

    private Transaction update(UUID transactionId, Consumer<Transaction> mutation) {
        return tx.execute(status -> {
            Transaction txn = transactions.findById(transactionId)
                    .orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_NOT_FOUND, null));
            mutation.accept(txn);
            return transactions.saveAndFlush(txn);
        });
    }
}
