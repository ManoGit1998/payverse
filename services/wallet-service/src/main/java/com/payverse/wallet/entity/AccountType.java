package com.payverse.wallet.entity;

/** Ledger account classification. User wallets are LIABILITY accounts (money the platform owes users). */
public enum AccountType {
    ASSET, LIABILITY, INCOME, EXPENSE
}
