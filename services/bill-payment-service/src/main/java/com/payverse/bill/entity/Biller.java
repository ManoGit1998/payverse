package com.payverse.bill.entity;

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
import java.util.UUID;
import java.util.regex.Pattern;

/** A biller. Recharge billers (fetch not supported) take any amount in range; others present a bill first. */
@Entity
@Table(name = "billers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Biller {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillCategory category;

    @Column(nullable = false)
    private String name;

    @Column(name = "account_label", nullable = false)
    private String accountLabel;

    @Column(name = "account_pattern", nullable = false)
    private String accountPattern;

    @Column(name = "fetch_supported", nullable = false)
    private boolean fetchSupported;

    @Column(name = "min_amount", nullable = false)
    private BigDecimal minAmount;

    @Column(name = "max_amount", nullable = false)
    private BigDecimal maxAmount;

    @Column(name = "adapter_code", nullable = false)
    private String adapterCode;

    @Column(nullable = false)
    private boolean active;

    public static Biller of(String code, BillCategory category, String name, String accountLabel, String accountPattern, boolean fetchSupported,
                            BigDecimal min, BigDecimal max) {
        Biller b = new Biller();
        b.id = UUID.randomUUID();
        b.code = code;
        b.category = category;
        b.name = name;
        b.accountLabel = accountLabel;
        b.accountPattern = accountPattern;
        b.fetchSupported = fetchSupported;
        b.minAmount = min;
        b.maxAmount = max;
        b.adapterCode = "MOCK";
        b.active = true;
        return b;
    }

    /** Account refs are compared upper-cased without spaces (vehicle numbers are typed in many ways). */
    public static String normalize(String accountRef) {
        return accountRef == null ? "" : accountRef.replaceAll("\\s+", "").toUpperCase();
    }

    public boolean acceptsAccount(String accountRef) {
        return Pattern.matches(accountPattern, normalize(accountRef));
    }

    public boolean acceptsAmount(Money amount) {
        return amount.amount().compareTo(minAmount) >= 0 && amount.amount().compareTo(maxAmount) <= 0;
    }
}
