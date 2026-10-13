package com.payverse.wallet.entity;

/** What a journal (one balanced posting) represents. */
public enum JournalType {
    ADD_MONEY, WITHDRAWAL, TRANSFER_DEBIT, TRANSFER_CREDIT, BILL_PAYMENT, BILL_REFUND, CASHBACK, REVERSAL
}
