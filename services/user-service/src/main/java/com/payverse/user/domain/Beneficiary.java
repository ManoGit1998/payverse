package com.payverse.user.domain;

import com.payverse.common.error.PayverseException;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A saved payee: a mobile number, a UPI ID (VPA) or a bank account (+ IFSC). */
public record Beneficiary(UUID id, UUID userId, Type type, String value, String ifsc, String nickname, Instant createdAt) {

    public enum Type { MOBILE, VPA, BANK_ACCOUNT }

    public static final int MAX_PER_USER = 50;
    private static final Pattern MOBILE = Pattern.compile("[6-9][0-9]{9}");
    private static final Pattern VPA = Pattern.compile("[a-z0-9.\\-_]{2,256}@[a-z]{2,64}");
    private static final Pattern ACCOUNT = Pattern.compile("[0-9]{9,18}");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final Pattern NICKNAME = Pattern.compile("[\\p{L}\\p{N} .'&()-]{1,50}");

    public Beneficiary {
        Objects.requireNonNull(id);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt);
        boolean valid = value != null && switch (type) {
            case MOBILE -> MOBILE.matcher(value).matches() && ifsc == null;
            case VPA -> VPA.matcher(value).matches() && ifsc == null;
            case BANK_ACCOUNT -> ACCOUNT.matcher(value).matches() && ifsc != null && IFSC.matcher(ifsc).matches();
        };
        if (!valid) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_INVALID, "Invalid " + type + " beneficiary");
        }
        if (nickname != null && !NICKNAME.matcher(nickname).matches()) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_INVALID, "Nickname must be 1-50 characters");
        }
    }

    public static Beneficiary save(UUID userId, Type type, String rawValue, String rawIfsc, String nickname, Instant now) {
        String value = rawValue == null ? null : switch (type) {
            case MOBILE -> rawValue.replaceAll("[\\s-]", "").replaceFirst("^(\\+91|91(?=[0-9]{10}$)|0(?=[0-9]{10}$))", "");
            case VPA -> rawValue.trim().toLowerCase(Locale.ROOT);
            case BANK_ACCOUNT -> rawValue.replaceAll("\\s", "");
        };
        String ifsc = rawIfsc == null || rawIfsc.isBlank() ? null : rawIfsc.trim().toUpperCase(Locale.ROOT);
        String nick = nickname == null || nickname.isBlank() ? null : nickname.trim();
        return new Beneficiary(UUID.randomUUID(), userId, type, value, ifsc, nick, now);
    }

    public String maskedValue() {
        return switch (type) {
            case MOBILE, BANK_ACCOUNT -> "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
            case VPA -> value;
        };
    }

    @Override
    public String toString() {
        return "Beneficiary[id=" + id + ", " + type + " " + maskedValue() + "]";
    }
}
