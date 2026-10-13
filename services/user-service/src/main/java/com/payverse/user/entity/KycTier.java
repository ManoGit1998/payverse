package com.payverse.user.entity;

/**
 * KYC tiers. {@code NONE}: mobile verified only. {@code MIN_KYC}: self-declared officially valid
 * document (PAN, voter ID, driving licence, passport). {@code FULL_KYC}: Aadhaar + document upload.
 * Wallet limits per tier are enforced by wallet-service.
 */
public enum KycTier {
    NONE, MIN_KYC, FULL_KYC;

    /** Upgrades go one step at a time: NONE → MIN_KYC → FULL_KYC. */
    public boolean canUpgradeTo(KycTier target) {
        return target.ordinal() == ordinal() + 1;
    }
}
