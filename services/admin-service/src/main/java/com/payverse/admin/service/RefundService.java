package com.payverse.admin.service;

import com.payverse.admin.client.BackOfficeClients;
import com.payverse.admin.entity.RefundRequest;
import com.payverse.admin.entity.RefundStatus;
import com.payverse.admin.exception.AdminErrorCode;
import com.payverse.admin.repository.RefundRequestRepository;
import com.payverse.common.error.PayverseException;
import com.payverse.common.events.Amount;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.AdminEvents;
import com.payverse.common.money.Money;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.common.web.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Maker-checker refunds. The approval is committed (with {@code admin.refund-approved} and an audit
 * entry) before transaction-service is called, so the decision is never lost; execution is then
 * retried until transaction-service answers COMPLETED or FAILED. The call is idempotent per
 * refund id, so retrying can never refund twice.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRequestRepository refunds;
    private final BackOfficeClients clients;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(RefundRequestRepository refunds, BackOfficeClients clients, OutboxPublisher outbox, AuditLogger audit,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.refunds = refunds;
        this.clients = clients;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Maker. Full refunds only: the amount is the transfer's amount. Joins the caller's transaction if any. */
    @Transactional
    public RefundRequest request(String actor, UUID transactionId, String reason, UUID disputeId) {
        BackOfficeClients.Transaction txn = clients.transaction(transactionId);
        if (!"SUCCESS".equals(txn.status())) {
            throw new PayverseException(AdminErrorCode.ADMIN_NOT_REFUNDABLE, "The transfer is " + txn.status());
        }
        RefundRequest refund = RefundRequest.request(transactionId, Money.of(txn.amount(), txn.currency()), reason, disputeId, actor, clock.instant());
        try {
            refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException e) {
            throw new PayverseException(AdminErrorCode.ADMIN_REFUND_EXISTS, null);
        }
        audit.record(actor, "REFUND_REQUESTED", "RefundRequest", refund.getId().toString(),
                Map.of("transactionId", transactionId.toString(), "amount", refund.getAmount().toPlainString()));
        return refund;
    }

    /** Checker: a different ADMIN approves, then the refund is executed right away. */
    public RefundRequest approve(String actor, UUID refundId) {
        tx.executeWithoutResult(status -> {
            RefundRequest r = find(refundId);
            decide(() -> r.approve(actor, clock.instant()), r);
            outbox.publish(EventType.REFUND_APPROVED, r.getId().toString(), new AdminEvents.RefundApproved(r.getId(), r.getTransactionId(),
                    Amount.of(r.getMoney()), r.getRequestedBy(), actor, r.getReason()));
            audit.record(actor, "REFUND_APPROVED", "RefundRequest", r.getId().toString(), Map.of("transactionId", r.getTransactionId().toString()));
        });
        execute(refundId);
        return tx.execute(status -> find(refundId));
    }

    @Transactional
    public RefundRequest reject(String actor, UUID refundId, String note) {
        RefundRequest r = find(refundId);
        decide(() -> r.reject(actor, clock.instant()), r);
        audit.record(actor, "REFUND_REJECTED", "RefundRequest", r.getId().toString(), Map.of("note", note == null ? "" : note));
        return r;
    }

    @Scheduled(fixedDelayString = "${payverse.admin.refund-retry-interval-ms:30000}")
    public void retryDue() {
        refunds.findExecutable(clock.instant(), PageRequest.of(0, 50)).forEach(this::execute);
    }

    void execute(UUID refundId) {
        RefundRequest r = tx.execute(status -> find(refundId));
        if (r == null || !r.isExecutable(clock.instant())) {
            return;
        }
        try {
            BackOfficeClients.RefundResult result = clients.refund(r.getTransactionId(), r.getId(), r.getReason());
            String refundStatus = result.transaction().refundStatus();
            if ("COMPLETED".equals(refundStatus) && r.getId().equals(result.transaction().refundId())) {
                update(refundId, x -> x.completed(clock.instant()));
            } else if ("FAILED".equals(refundStatus) && r.getId().equals(result.transaction().refundId())) {
                update(refundId, x -> x.failed(result.transaction().refundFailureCode(), clock.instant()));
            } else {
                update(refundId, x -> x.retryLater("PENDING", clock.instant()));
            }
        } catch (PayverseException e) {
            if (e.errorCode().httpStatus() >= 500) {
                log.warn("Refund {} will be retried: {}", refundId, e.getMessage());
                update(refundId, x -> x.retryLater(e.errorCode().code(), clock.instant()));
            } else {
                log.warn("Refund {} failed: {}", refundId, e.errorCode().code());
                update(refundId, x -> x.failed(e.errorCode().code(), clock.instant()));
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<RefundRequest> list(RefundStatus status, int page, int size) {
        return status == null ? refunds.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : refunds.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public RefundRequest get(UUID refundId) {
        return find(refundId);
    }

    private void update(UUID refundId, Consumer<RefundRequest> change) {
        try {
            tx.executeWithoutResult(status -> change.accept(find(refundId)));
        } catch (OptimisticLockingFailureException e) {
            log.info("Refund {} was updated concurrently", refundId);
        }
    }

    private static void decide(Runnable decision, RefundRequest r) {
        if (r.getStatus() != RefundStatus.REQUESTED) {
            throw new PayverseException(AdminErrorCode.ADMIN_REFUND_DECIDED, "Refund is " + r.getStatus());
        }
        try {
            decision.run();
        } catch (IllegalArgumentException e) {
            throw new PayverseException(AdminErrorCode.ADMIN_MAKER_CHECKER, null);
        }
    }

    private RefundRequest find(UUID refundId) {
        return refunds.findById(refundId).orElseThrow(() -> new PayverseException(AdminErrorCode.ADMIN_REFUND_NOT_FOUND, null));
    }
}
