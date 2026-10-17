package com.payverse.bill.service;

import com.payverse.bill.client.AuthClient;
import com.payverse.bill.entity.BillPayment;
import com.payverse.bill.entity.BillPaymentStatus;
import com.payverse.bill.entity.Biller;
import com.payverse.bill.entity.ExecutionStatus;
import com.payverse.bill.entity.Mandate;
import com.payverse.bill.entity.MandateExecution;
import com.payverse.bill.entity.MandateFrequency;
import com.payverse.bill.entity.PaymentSource;
import com.payverse.bill.exception.BillErrorCode;
import com.payverse.bill.model.FetchedBill;
import com.payverse.bill.repository.MandateExecutionRepository;
import com.payverse.bill.repository.MandateRepository;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.masking.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Auto-pay mandates. Creating one needs the PIN (consent). The scheduler claims each due run under a
 * row lock (one execution row per run, unique), then pays it like a normal bill with the key
 * {@code mandate:<id>:<run time>} — so a crashed or repeated run can never pay twice.
 */
@Service
public class MandateService {

    public record CreateMandate(UUID userId, String billerCode, String accountRef, MandateFrequency frequency, Money maxAmount,
                                Money fixedAmount, Instant firstRun, String pin) {

        @Override
        public String toString() {
            return "CreateMandate[user=" + userId + ", biller=" + billerCode + ", pin=****]";
        }
    }

    private record ClaimedRun(Mandate mandate, MandateExecution execution) {
    }

    private static final Logger log = LoggerFactory.getLogger(MandateService.class);
    private static final DateTimeFormatter RUN_KEY = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private final MandateRepository mandates;
    private final MandateExecutionRepository executions;
    private final BillerService billers;
    private final BillPaymentService payments;
    private final AuthClient auth;
    private final BillEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MandateService(MandateRepository mandates, MandateExecutionRepository executions, BillerService billers, BillPaymentService payments,
                          AuthClient auth, BillEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.mandates = mandates;
        this.executions = executions;
        this.billers = billers;
        this.payments = payments;
        this.auth = auth;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Mandate create(CreateMandate c) {
        Biller biller = billers.get(c.billerCode());
        String account = billers.validAccount(biller, c.accountRef());
        if (c.fixedAmount() != null && !biller.acceptsAmount(c.fixedAmount())) {
            throw new PayverseException(BillErrorCode.BILL_AMOUNT_OUT_OF_RANGE, null);
        }
        if (c.pin() == null || c.pin().isBlank()) {
            throw new PayverseException(BillErrorCode.BILL_PIN_REQUIRED, null);
        }
        auth.verifyPin(c.userId(), c.pin());
        Instant now = clock.instant();
        Mandate mandate;
        try {
            mandate = Mandate.create(c.userId(), biller, account, PiiMasker.maskAccountNumber(account), c.frequency(), c.maxAmount(),
                    c.fixedAmount(), c.firstRun() == null ? now : c.firstRun(), now);
        } catch (IllegalArgumentException e) {
            throw new PayverseException(BillErrorCode.BILL_INVALID_MANDATE, e.getMessage());
        }
        return tx.execute(status -> mandates.save(mandate));
    }

    @Transactional(readOnly = true)
    public List<Mandate> list(UUID userId) {
        return mandates.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public Mandate cancel(UUID userId, UUID mandateId) {
        Mandate mandate = owned(userId, mandateId);
        mandate.cancel(clock.instant());
        return mandate;
    }

    @Transactional(readOnly = true)
    public List<MandateExecution> executions(UUID userId, UUID mandateId) {
        owned(userId, mandateId);
        return executions.findByMandateIdOrderByScheduledForDesc(mandateId);
    }

    @Scheduled(fixedDelayString = "${payverse.bill.mandate-interval-ms:60000}")
    public int runDue() {
        List<UUID> due = mandates.findDue(clock.instant(), PageRequest.of(0, 100));
        int ran = 0;
        for (UUID id : due) {
            ClaimedRun run = tx.execute(status -> claim(id));
            if (run != null) {
                execute(run);
                ran++;
            }
        }
        return ran;
    }

    private ClaimedRun claim(UUID mandateId) {
        Mandate mandate = mandates.lockById(mandateId).orElse(null);
        if (mandate == null || !mandate.isDue(clock.instant())) {
            return null; // another instance claimed it
        }
        Instant scheduled = mandate.claimRun();
        MandateExecution execution = executions.save(MandateExecution.start(mandate.getId(), scheduled, clock.instant()));
        return new ClaimedRun(mandate, execution);
    }

    private void execute(ClaimedRun run) {
        Mandate m = run.mandate();
        UUID executionId = run.execution().getId();
        try {
            Money amount = m.getFixedMoney();
            if (amount == null) {
                FetchedBill bill = billers.fetch(m.getBillerCode(), m.getAccountRef());
                if (bill.amountDue().isGreaterThan(m.getMaxMoney())) {
                    finish(executionId, m.getUserId(), ExecutionStatus.SKIPPED, "Bill INR " + bill.amountDue().toPlainString()
                            + " is above the auto-pay limit INR " + m.getMaxMoney().toPlainString());
                    return;
                }
                amount = bill.amountDue();
            }
            Money paid = amount;
            String key = "mandate:" + m.getId() + ":" + RUN_KEY.format(run.execution().getScheduledFor());
            // a payment still PENDING here is closed by the processor when it settles
            BillPaymentService.PayResult result = payments.pay(new BillPaymentService.PayCommand(m.getUserId(), m.getBillerCode(),
                    m.getAccountRef(), paid, null, key, PaymentSource.MANDATE, m.getId()));
            tx.executeWithoutResult(status -> executions.findById(executionId).ifPresent(e -> {
                e.attach(result.payment().getId(), paid);
                BillPayment p = result.payment();
                if (p.isSettled() && e.complete(p.getStatus() == BillPaymentStatus.SUCCESS ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED,
                        p.getFailureCode(), clock.instant())) {
                    events.mandateExecuted(e, m.getUserId());
                }
            }));
        } catch (PayverseException e) {
            log.warn("Mandate {} run failed: {}", m.getId(), e.errorCode().code());
            finish(executionId, m.getUserId(), ExecutionStatus.FAILED, e.errorCode().code());
        }
    }

    private void finish(UUID executionId, UUID userId, ExecutionStatus status, String reason) {
        tx.executeWithoutResult(s -> executions.findById(executionId).ifPresent(e -> {
            if (e.complete(status, reason, clock.instant())) {
                events.mandateExecuted(e, userId);
            }
        }));
    }

    private Mandate owned(UUID userId, UUID mandateId) {
        return mandates.findById(mandateId).filter(m -> m.getUserId().equals(userId))
                .orElseThrow(() -> new PayverseException(BillErrorCode.BILL_MANDATE_NOT_FOUND, null));
    }
}
