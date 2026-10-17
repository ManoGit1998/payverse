package com.payverse.payment.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.client.RemoteErrorCode;
import com.payverse.payment.client.TransactionClient;
import com.payverse.payment.entity.CollectRequest;
import com.payverse.payment.entity.Vpa;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.repository.CollectRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * UPI collect: the payee requests money from a payer VPA; the payer approves with their PIN, which
 * starts an idempotent transfer in transaction-service (key {@code collect:<id>}), or rejects.
 */
@Service
public class CollectRequestService {

    public enum Direction { INCOMING, OUTGOING }

    private final CollectRequestRepository collects;
    private final VpaService vpaService;
    private final TransactionClient transactions;
    private final PaymentEventPublisher events;
    private final Clock clock;

    public CollectRequestService(CollectRequestRepository collects, VpaService vpaService, TransactionClient transactions,
                                 PaymentEventPublisher events, Clock clock) {
        this.collects = collects;
        this.vpaService = vpaService;
        this.transactions = transactions;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public CollectRequest create(UUID payeeUserId, String payerVpa, Money amount, String note) {
        if (!amount.isPositive()) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_AMOUNT, null);
        }
        Vpa payee = vpaService.ensureDefault(payeeUserId);
        vpaService.resolve(payerVpa); // goes through the (mock) switch: rejects external / unknown handles
        Vpa payer = vpaService.require(payerVpa);
        CollectRequest request = collects.save(CollectRequest.create(payer, payee, amount, note, clock.instant()));
        events.collectRequested(request);
        return request;
    }

    @Transactional
    public Page<CollectRequest> list(UUID userId, Direction direction, int page, int size) {
        Page<CollectRequest> result = direction == Direction.INCOMING
                ? collects.findByPayerUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                : collects.findByPayeeUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        result.forEach(r -> r.isActionable(clock.instant())); // lazy expiry
        return result;
    }

    /** The payer approves: verifies the PIN and moves the money through transaction-service. */
    @Transactional(noRollbackFor = PayverseException.class)
    public CollectRequest approve(UUID payerUserId, UUID collectId, String pin) {
        CollectRequest request = owned(collectId, payerUserId, true);
        request.ensureActionable(clock.instant());
        TransactionClient.TransferOutcome outcome = transactions.transfer(request.getPayerUserId(), request.getPayeeUserId(),
                request.getPayeeVpa(), request.getAmountMoney(), request.getNote(), "COLLECT", request.getId(), pin,
                "collect:" + request.getId());
        request.recordTransfer(outcome.transactionId(), outcome.status(), clock.instant());
        if ("FAILED".equals(outcome.status())) {
            throw new PayverseException(new RemoteErrorCode(
                    outcome.failureCode() == null ? "TXN_FAILED" : outcome.failureCode(), 422, "Payment failed"),
                    outcome.failureReason(), Map.of("transactionId", outcome.transactionId().toString()));
        }
        return request;
    }

    @Transactional
    public CollectRequest reject(UUID payerUserId, UUID collectId) {
        CollectRequest request = owned(collectId, payerUserId, true);
        request.reject(clock.instant());
        return request;
    }

    /** From transaction events: settles approvals whose transfer was still in progress. */
    @Transactional
    public void onTransferOutcome(UUID transactionId, boolean success) {
        collects.findByTransactionId(transactionId).forEach(r -> r.settle(success, clock.instant()));
    }

    private CollectRequest owned(UUID collectId, UUID userId, boolean asPayer) {
        CollectRequest request = collects.findById(collectId)
                .orElseThrow(() -> new PayverseException(PaymentErrorCode.PAYMENT_COLLECT_NOT_FOUND, null));
        UUID owner = asPayer ? request.getPayerUserId() : request.getPayeeUserId();
        if (!owner.equals(userId)) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_COLLECT_NOT_FOUND, null);
        }
        return request;
    }
}
