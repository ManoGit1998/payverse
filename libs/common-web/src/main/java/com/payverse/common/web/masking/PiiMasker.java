package com.payverse.common.web.masking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks personally identifiable information for API responses and logs. */
public final class PiiMasker {

    private static final Pattern INDIAN_MOBILE = Pattern.compile("(?<![0-9])(?:\\+?91[- ]?)?([6-9][0-9]{9})(?![0-9])");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![0-9])[0-9]{11,18}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private PiiMasker() {
    }

    public static String mask(String value, MaskType type) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (type) {
            case MOBILE, ACCOUNT_NUMBER, ID -> keepLast(value, 4);
            case EMAIL -> maskEmail(value);
            case FULL -> "****";
        };
    }

    public static String maskMobile(String mobile) {
        return mask(mobile, MaskType.MOBILE);
    }

    public static String maskAccountNumber(String accountNumber) {
        return mask(accountNumber, MaskType.ACCOUNT_NUMBER);
    }

    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return keepLast(email, 2);
        }
        return email.charAt(0) + "*".repeat(Math.max(at - 1, 1)) + email.substring(at);
    }

    /**
     * Best-effort scrubbing of free text (log messages): Indian mobile numbers, long digit runs
     * (account / card / Aadhaar numbers) and e-mail addresses. A safety net — code should still
     * never log raw PII.
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = replace(INDIAN_MOBILE, text, m -> keepLast(m.group(1), 4));
        result = replace(LONG_DIGITS, result, m -> keepLast(m.group(), 4));
        result = replace(EMAIL, result, m -> m.group(1) + "***" + m.group(2));
        return result;
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        do {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String keepLast(String value, int visible) {
        if (value.length() <= visible) {
            return "*".repeat(value.length());
        }
        return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }
}
