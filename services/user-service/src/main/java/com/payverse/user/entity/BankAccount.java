package com.payverse.user.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.EncryptedStringConverter;
import com.payverse.user.exception.UserErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** A linked bank account. The number is encrypted at rest and only ever exposed masked. */
@Entity
@Table(name = "bank_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankAccount {

    public static final int MAX_PER_USER = 5;
    private static final Pattern ACCOUNT = Pattern.compile("[0-9]{9,18}");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final Pattern HOLDER = Pattern.compile("[\\p{L} .'-]{2,100}");

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_number_enc", nullable = false)
    private String accountNumber;

    /** HMAC blind index for duplicate detection. */
    @Column(name = "account_hash", nullable = false)
    private String accountHash;

    @Column(nullable = false)
    private String ifsc;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    /** Normalises and validates the details; {@code indexer} computes the blind index of the number. */
    public static BankAccount link(UUID userId, String rawNumber, String rawIfsc, String rawHolder, boolean primary,
                                   UnaryOperator<String> indexer, Instant now) {
        String number = rawNumber == null ? null : rawNumber.replaceAll("\\s", "");
        String ifsc = rawIfsc == null ? null : rawIfsc.trim().toUpperCase(Locale.ROOT);
        String holder = rawHolder == null ? null : rawHolder.trim();
        if (number == null || !ACCOUNT.matcher(number).matches()) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_INVALID, "Account number must be 9-18 digits");
        }
        if (ifsc == null || !IFSC.matcher(ifsc).matches()) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_INVALID, "Invalid IFSC code");
        }
        if (holder == null || !HOLDER.matcher(holder).matches()) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_INVALID, "Invalid account holder name");
        }
        BankAccount account = new BankAccount();
        account.id = UUID.randomUUID();
        account.userId = userId;
        account.accountNumber = number;
        account.accountHash = indexer.apply(number);
        account.ifsc = ifsc;
        account.holderName = holder;
        account.primary = primary;
        account.createdAt = now;
        return account;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public void remove(Instant now) {
        removedAt = now;
        primary = false;
    }

    public String getMaskedNumber() {
        return "X".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    /** Bank code = first four IFSC characters (e.g. HDFC). */
    public String getBankCode() {
        return ifsc.substring(0, 4);
    }

    @Override
    public String toString() {
        return "BankAccount[id=" + id + ", " + getMaskedNumber() + ", " + ifsc + "]";
    }
}
