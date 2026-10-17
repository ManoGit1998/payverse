package com.payverse.rewards.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** A cashback coupon: each user may redeem it once, up to {@code usageLimit} redemptions in total. */
@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    public enum Problem { INACTIVE, NOT_STARTED, EXPIRED, EXHAUSTED }

    public static final Pattern CODE = Pattern.compile("^[A-Z0-9]{4,32}$");

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal cashback;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "usage_limit", nullable = false)
    private int usageLimit;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    public static Coupon create(String code, String description, Money cashback, Instant validFrom, Instant validUntil,
                                int usageLimit, Instant now) {
        String normalized = normalize(code);
        if (!CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Coupon codes are 4-32 letters and digits");
        }
        if (!cashback.isPositive() || usageLimit <= 0 || !validFrom.isBefore(validUntil)) {
            throw new IllegalArgumentException("Coupons need a positive cashback and usage limit and a valid window");
        }
        Coupon coupon = new Coupon();
        coupon.id = UUID.randomUUID();
        coupon.code = normalized;
        coupon.description = description;
        coupon.cashback = cashback.amount();
        coupon.currency = cashback.currency().getCurrencyCode();
        coupon.validFrom = validFrom;
        coupon.validUntil = validUntil;
        coupon.usageLimit = usageLimit;
        coupon.active = true;
        coupon.createdAt = now;
        return coupon;
    }

    public static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public Money getCashbackMoney() {
        return Money.of(cashback, currency);
    }

    /** @return why the coupon can't be redeemed now, or null if it can */
    public Problem problemAt(Instant now) {
        if (!active) {
            return Problem.INACTIVE;
        }
        if (now.isBefore(validFrom)) {
            return Problem.NOT_STARTED;
        }
        if (!now.isBefore(validUntil)) {
            return Problem.EXPIRED;
        }
        return usedCount >= usageLimit ? Problem.EXHAUSTED : null;
    }

    public void redeem(Instant now) {
        Problem problem = problemAt(now);
        if (problem != null) {
            throw new IllegalStateException("Coupon " + code + " cannot be redeemed: " + problem);
        }
        usedCount++;
    }

    public void setActive(boolean isActive) {
        active = isActive;
    }
}
