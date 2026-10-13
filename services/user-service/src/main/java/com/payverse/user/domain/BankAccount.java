package com.payverse.user.domain;

import com.payverse.common.error.PayverseException;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A linked bank account (number encrypted at rest, masked in responses). */
public record BankAccount(UUID id, UUID userId, String accountNumber, String ifsc, String holderName, boolean primary,
                          Instant createdAt) {

    public static final int MAX_PER_USER = 5;
    private static final Pattern ACCOUNT = Pattern.compile("[0-9]{9,18}");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final Pattern HOLDER = Pattern.compile("[\\p{L} .'-]{2,100}");

    public BankAccount {
        Objects.requireNonNull(id);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(createdAt);
        if (accountNumber == null || !ACCOUNT.matcher(accountNumber).matches()) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_INVALID, "Account number must be 9-18 digits");
        }
        if (ifsc == null || !IFSC.matcher(ifsc).matches()) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_INVALID, "Invalid IFSC code");
        }
        if (holderName == null || !HOLDER.matcher(holderName).matches()) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_INVALID, "Invalid account holder name");
        }
    }

    public static BankAccount link(UUID userId, String accountNumber, String ifsc, String holderName, boolean primary, Instant now) {
        return new BankAccount(UUID.randomUUID(), userId, accountNumber == null ? null : accountNumber.replaceAll("\\s", ""),
                ifsc == null ? null : ifsc.trim().toUpperCase(Locale.ROOT), holderName == null ? null : holderName.trim(), primary, now);
    }

    public BankAccount withPrimary(boolean isPrimary) {
        return new BankAccount(id, userId, accountNumber, ifsc, holderName, isPrimary, createdAt);
    }

    public String maskedNumber() {
        return "X".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    /** Bank code = first four IFSC characters (e.g. HDFC). */
    public String bankCode() {
        return ifsc.substring(0, 4);
    }

    @Override
    public String toString() {
        return "BankAccount[id=" + id + ", " + maskedNumber() + ", " + ifsc + "]";
    }
}
