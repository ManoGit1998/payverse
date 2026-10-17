package com.payverse.payment.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A settlement reported by the UPI switch (RRN = retrieval reference number). */
@Entity
@Table(name = "upi_switch_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UpiSwitchRecord {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String rrn;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "payer_vpa")
    private String payerVpa;

    @Column(name = "payee_vpa")
    private String payeeVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static UpiSwitchRecord settled(String rrn, UUID transactionId, String payerVpa, String payeeVpa, Money amount, Instant now) {
        UpiSwitchRecord record = new UpiSwitchRecord();
        record.id = UUID.randomUUID();
        record.rrn = rrn;
        record.transactionId = transactionId;
        record.payerVpa = payerVpa;
        record.payeeVpa = payeeVpa;
        record.amount = amount.amount();
        record.status = "SUCCESS";
        record.createdAt = now;
        return record;
    }
}
