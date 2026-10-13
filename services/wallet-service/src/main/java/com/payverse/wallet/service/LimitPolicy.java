package com.payverse.wallet.service;

/** Which KYC-tier limits a posting is subject to. */
public enum LimitPolicy {
    /** Spending: daily debit limit + balance. */
    DEBIT,
    /** Incoming money (P2P, cashback, refunds): max balance. */
    CREDIT,
    /** Add money from outside: max balance + monthly load limit. */
    LOAD,
    /** Compensations / reversals: no limits, allowed on frozen wallets. */
    NONE
}
