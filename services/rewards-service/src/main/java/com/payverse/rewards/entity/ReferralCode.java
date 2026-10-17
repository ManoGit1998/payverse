package com.payverse.rewards.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** A user's own referral code: 8 characters from an alphabet without look-alikes (no 0/O, 1/I/L). */
@Entity
@Table(name = "referral_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReferralCode {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 8;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ReferralCode generate(UUID userId, RandomGenerator random, Instant now) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        ReferralCode referral = new ReferralCode();
        referral.userId = userId;
        referral.code = code.toString();
        referral.createdAt = now;
        return referral;
    }

    public static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
