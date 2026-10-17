package com.payverse.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One status transition of a transaction (audit trail of the state machine). */
@Entity
@Table(name = "transaction_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionStatusHistory {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private TransactionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private TransactionStatus toStatus;

    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public static TransactionStatusHistory of(Transaction.StatusChange change) {
        TransactionStatusHistory history = new TransactionStatusHistory();
        history.id = UUID.randomUUID();
        history.transactionId = change.transactionId();
        history.fromStatus = change.from();
        history.toStatus = change.to();
        history.reason = change.reason() == null || change.reason().length() <= 200 ? change.reason() : change.reason().substring(0, 200);
        history.changedAt = change.at();
        return history;
    }

    public static TransactionStatusHistory created(Transaction txn) {
        return of(new Transaction.StatusChange(txn.getId(), null, TransactionStatus.INITIATED, "created", txn.getCreatedAt()));
    }
}
