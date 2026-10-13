package com.payverse.wallet.entity;

/** Frozen wallets reject debits and new credits (reversals still apply). */
public enum WalletStatus {
    ACTIVE, FROZEN
}
