package com.payverse.payment.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A QR code issued by PayVerse. STATIC = VPA only (reusable); DYNAMIC = fixed amount with expiry. */
@Entity
@Table(name = "qr_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QrCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String vpa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QrType type;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    private String note;

    @Column(nullable = false)
    private String payload;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static QrCode issue(UUID id, UUID userId, String vpa, QrType type, Money amount, String note, String payload,
                               Instant expiresAt, Instant now) {
        QrCode qr = new QrCode();
        qr.id = id;
        qr.userId = userId;
        qr.vpa = vpa;
        qr.type = type;
        qr.amount = amount == null ? null : amount.amount();
        qr.note = note;
        qr.payload = payload;
        qr.expiresAt = expiresAt;
        qr.createdAt = now;
        return qr;
    }

    public Money getAmountMoney() {
        return amount == null ? null : Money.inr(amount);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
