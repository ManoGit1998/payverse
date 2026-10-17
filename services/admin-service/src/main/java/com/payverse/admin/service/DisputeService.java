package com.payverse.admin.service;

import com.payverse.admin.client.BackOfficeClients;
import com.payverse.admin.entity.Dispute;
import com.payverse.admin.entity.DisputeOutcome;
import com.payverse.admin.entity.DisputeStatus;
import com.payverse.admin.entity.RefundRequest;
import com.payverse.admin.exception.AdminErrorCode;
import com.payverse.admin.repository.DisputeRepository;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Users raise disputes on their completed transfers; support reviews them and either rejects or requests a refund. */
@Service
public class DisputeService {

    private final DisputeRepository disputes;
    private final RefundService refunds;
    private final BackOfficeClients clients;
    private final AuditLogger audit;
    private final Clock clock;

    public DisputeService(DisputeRepository disputes, RefundService refunds, BackOfficeClients clients, AuditLogger audit, Clock clock) {
        this.disputes = disputes;
        this.refunds = refunds;
        this.clients = clients;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Dispute open(UUID userId, UUID transactionId, String reason) {
        BackOfficeClients.Transaction txn;
        try {
            txn = clients.transaction(transactionId);
        } catch (PayverseException e) {
            if (e.errorCode().httpStatus() == 404) {
                throw new PayverseException(AdminErrorCode.ADMIN_INVALID_DISPUTE, "No such transaction");
            }
            throw e;
        }
        if (!userId.equals(txn.payerUserId()) && !userId.equals(txn.payeeUserId())) {
            throw new PayverseException(AdminErrorCode.ADMIN_INVALID_DISPUTE, "No such transaction"); // don't reveal others' transactions
        }
        if (!"SUCCESS".equals(txn.status())) {
            throw new PayverseException(AdminErrorCode.ADMIN_NOT_REFUNDABLE, "The transfer is " + txn.status());
        }
        Dispute dispute;
        try {
            dispute = disputes.saveAndFlush(Dispute.open(transactionId, userId, reason, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new PayverseException(AdminErrorCode.ADMIN_DISPUTE_EXISTS, null);
        } catch (IllegalArgumentException e) {
            throw new PayverseException(AdminErrorCode.ADMIN_INVALID_DISPUTE, e.getMessage());
        }
        audit.record(userId.toString(), "DISPUTE_OPENED", "Dispute", dispute.getId().toString(), Map.of("transactionId", transactionId.toString()));
        return dispute;
    }

    @Transactional(readOnly = true)
    public List<Dispute> mine(UUID userId) {
        return disputes.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Page<Dispute> list(DisputeStatus status, int page, int size) {
        return status == null ? disputes.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : disputes.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Dispute get(UUID id) {
        return find(id);
    }

    @Transactional
    public Dispute assign(String agent, UUID id) {
        Dispute d = find(id);
        requireOpen(d);
        d.assign(agent, clock.instant());
        audit.record(agent, "DISPUTE_ASSIGNED", "Dispute", d.getId().toString(), Map.of());
        return d;
    }

    /** REFUND creates a refund request (this agent is the maker; another ADMIN must approve it). */
    @Transactional
    public Dispute resolve(String agent, UUID id, DisputeOutcome outcome, String note) {
        Dispute d = find(id);
        requireOpen(d);
        UUID refundId = null;
        if (outcome == DisputeOutcome.REFUND) {
            RefundRequest refund = refunds.request(agent, d.getTransactionId(), "Dispute: " + (note == null ? d.getReason() : note), d.getId());
            refundId = refund.getId();
        }
        d.resolve(outcome, agent, note, refundId, clock.instant());
        audit.record(agent, "DISPUTE_RESOLVED", "Dispute", d.getId().toString(), Map.of("outcome", outcome.name()));
        return d;
    }

    private static void requireOpen(Dispute d) {
        if (d.getStatus() == DisputeStatus.RESOLVED) {
            throw new PayverseException(AdminErrorCode.ADMIN_DISPUTE_RESOLVED, null);
        }
    }

    private Dispute find(UUID id) {
        return disputes.findById(id).orElseThrow(() -> new PayverseException(AdminErrorCode.ADMIN_DISPUTE_NOT_FOUND, null));
    }
}
