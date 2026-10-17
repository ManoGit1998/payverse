package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.transaction.dto.TransactionResponse;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.entity.TransactionStatusHistory;
import com.payverse.transaction.exception.TransactionErrorCode;
import com.payverse.transaction.repository.TransactionRepository;
import com.payverse.transaction.repository.TransactionStatusHistoryRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Transaction history (filters + paging), details and statement rows — always scoped to the viewer. */
@Service
public class TransactionQueryService {

    public record HistoryFilter(TransactionResponse.Direction direction, TransactionStatus status, Instant from, Instant to,
                                Money minAmount, Money maxAmount) {
    }

    private static final Set<String> SORTABLE = Set.of("createdAt", "amount");

    private final TransactionRepository transactions;
    private final TransactionStatusHistoryRepository history;

    public TransactionQueryService(TransactionRepository transactions, TransactionStatusHistoryRepository history) {
        this.transactions = transactions;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public Transaction get(UUID viewer, UUID transactionId) {
        return transactions.findById(transactionId).filter(t -> t.involves(viewer))
                .orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_NOT_FOUND, null));
    }

    @Transactional(readOnly = true)
    public List<TransactionStatusHistory> statusHistory(UUID transactionId) {
        return history.findByTransactionIdOrderByChangedAt(transactionId);
    }

    @Transactional(readOnly = true)
    public Page<Transaction> history(UUID viewer, HistoryFilter filter, int page, int size, String sortField, Sort.Direction sortDirection) {
        String field = SORTABLE.contains(sortField) ? sortField : "createdAt";
        return transactions.findAll(spec(viewer, filter), PageRequest.of(page, size, Sort.by(sortDirection, field)));
    }

    /** Settled money movements in a period, oldest first, for statements. */
    @Transactional(readOnly = true)
    public List<Transaction> statementRows(UUID viewer, Instant from, Instant to) {
        Specification<Transaction> settled = (root, query, cb) -> root.get("status").in(TransactionStatus.SUCCESS, TransactionStatus.REVERSED);
        return transactions.findAll(spec(viewer, new HistoryFilter(null, null, from, to, null, null)).and(settled),
                Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    private static Specification<Transaction> spec(UUID viewer, HistoryFilter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.direction() == TransactionResponse.Direction.SENT) {
                predicates.add(cb.equal(root.get("payerUserId"), viewer));
            } else if (f.direction() == TransactionResponse.Direction.RECEIVED) {
                predicates.add(cb.equal(root.get("payeeUserId"), viewer));
                predicates.add(root.get("status").in(TransactionStatus.SUCCESS, TransactionStatus.REVERSED)); // payees never see failed attempts
            } else {
                predicates.add(cb.or(cb.equal(root.get("payerUserId"), viewer),
                        cb.and(cb.equal(root.get("payeeUserId"), viewer),
                                root.get("status").in(TransactionStatus.SUCCESS, TransactionStatus.REVERSED))));
            }
            if (f.status() != null) {
                predicates.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), f.to()));
            }
            if (f.minAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), f.minAmount().amount()));
            }
            if (f.maxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), f.maxAmount().amount()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
