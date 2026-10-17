package com.payverse.bill.service;

import com.payverse.bill.client.WalletClient;
import com.payverse.bill.config.BillProperties;
import com.payverse.bill.entity.BillPayment;
import com.payverse.bill.entity.BillPaymentStatus;
import com.payverse.bill.entity.Biller;
import com.payverse.bill.entity.ExecutionStatus;
import com.payverse.bill.exception.BillErrorCode;
import com.payverse.bill.repository.BillPaymentRepository;
import com.payverse.bill.repository.BillerRepository;
import com.payverse.bill.repository.MandateExecutionRepository;
import com.payverse.common.error.PayverseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Drives a bill payment through its steps. Remote calls (wallet, biller) happen outside database
 * transactions; every state change is committed on its own, guarded by {@code @Version}, so a
 * concurrent runner simply stops. Wallet steps are idempotent per payment, and the biller is
 * idempotent per payment id, so a resumed payment can never pay or refund twice.
 */
@Service
public class BillPaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentProcessor.class);

    private final BillPaymentRepository payments;
    private final BillerRepository billers;
    private final MandateExecutionRepository executions;
    private final WalletClient wallet;
    private final BillerGateway biller;
    private final BillEventPublisher events;
    private final BillProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BillPaymentProcessor(BillPaymentRepository payments, BillerRepository billers, MandateExecutionRepository executions,
                                WalletClient wallet, BillerGateway biller, BillEventPublisher events, BillProperties properties,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.payments = payments;
        this.billers = billers;
        this.executions = executions;
        this.wallet = wallet;
        this.biller = biller;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void run(UUID paymentId) {
        try {
            for (int i = 0; i < 5; i++) {
                BillPayment p = payments.findById(paymentId).orElseThrow();
                if (!p.isDue(clock.instant()) || !step(p)) {
                    return;
                }
            }
        } catch (OptimisticLockingFailureException e) {
            log.info("Bill payment {} is being processed elsewhere", paymentId);
        }
    }

    @Scheduled(fixedDelayString = "${payverse.bill.recovery-interval-ms:10000}")
    public void recover() {
        Instant now = clock.instant();
        List<UUID> due = payments.findRecoverable(now, now.minus(properties.staleAfter()), PageRequest.of(0, 50));
        due.forEach(this::run);
    }

    /** @return true to continue with the next step right away */
    private boolean step(BillPayment p) {
        return switch (p.getStep()) {
            case NOT_STARTED -> debit(p);
            case DEBITED -> payBiller(p);
            case REFUNDING -> refund(p);
            case PAID, REFUNDED -> false;
        };
    }

    private boolean debit(BillPayment p) {
        try {
            WalletClient.Posting posting = wallet.debit(p.getUserId(), p.getMoney(), p.getId(), describe(p));
            update(p.getId(), b -> b.recordDebit(posting.journalId(), now()));
            return true;
        } catch (PayverseException e) {
            if (retryable(e)) {
                return retryLater(p, "debit: " + e.getMessage());
            }
            update(p.getId(), b -> {
                b.failBeforeDebit(e.errorCode().code(), e.getMessage(), now());
                settled(b);
            });
            return false;
        }
    }

    private boolean payBiller(BillPayment p) {
        Biller b = billers.findByCode(p.getBillerCode()).orElseThrow();
        try {
            String reference = biller.pay(b, p.getAccountRef(), p.getMoney(), p.getId());
            update(p.getId(), bp -> {
                bp.recordBillerSuccess(reference, now());
                settled(bp);
            });
            return false;
        } catch (BillerGateway.BillerDeclinedException e) {
            log.info("Biller declined bill payment {}: refunding", p.getId());
            update(p.getId(), bp -> bp.startRefund(BillErrorCode.BILL_BILLER_DECLINED.code(), e.getMessage(), now()));
            return true;
        } catch (BillerGateway.BillerUnavailableException e) {
            return retryLater(p, "biller: " + e.getMessage());
        }
    }

    private boolean refund(BillPayment p) {
        try {
            WalletClient.Posting posting = wallet.refund(p.getUserId(), p.getMoney(), p.getId(), "Refund: " + describe(p));
            update(p.getId(), b -> {
                b.recordRefund(posting.journalId(), now());
                settled(b);
            });
        } catch (PayverseException e) {
            // a refund must eventually succeed (reversal-like credits are not refused by limits for long)
            retryLater(p, "refund: " + e.getMessage());
        }
        return false;
    }

    /** Terminal: publish the outcome and close the mandate run, in the same transaction as the state change. */
    private void settled(BillPayment p) {
        if (p.getStatus() == BillPaymentStatus.SUCCESS) {
            events.completed(p, billers.findByCode(p.getBillerCode()).orElseThrow());
        } else {
            events.failed(p);
        }
        if (p.getMandateId() != null) {
            executions.findByBillPaymentId(p.getId()).ifPresent(e -> {
                if (e.complete(p.getStatus() == BillPaymentStatus.SUCCESS ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED,
                        p.getFailureCode(), now())) {
                    events.mandateExecuted(e, p.getUserId());
                }
            });
        }
    }

    private boolean retryLater(BillPayment p, String reason) {
        log.warn("Bill payment {} will be retried: {}", p.getId(), reason);
        update(p.getId(), b -> b.scheduleRetry(reason, now()));
        return false;
    }

    private void update(UUID id, Consumer<BillPayment> mutation) {
        tx.executeWithoutResult(status -> {
            BillPayment p = payments.findById(id).orElseThrow();
            mutation.accept(p);
            payments.saveAndFlush(p);
        });
    }

    static boolean retryable(PayverseException e) {
        int status = e.errorCode().httpStatus();
        return status >= 500 || status == 409 || status == 429 || status == 408;
    }

    private static String describe(BillPayment p) {
        return p.getCategory() + " bill " + p.getBillerCode() + " " + p.getAccountRefMasked();
    }

    private Instant now() {
        return clock.instant();
    }
}
