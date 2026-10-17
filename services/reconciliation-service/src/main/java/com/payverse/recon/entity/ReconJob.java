package com.payverse.recon.entity;

/**
 * The reconciliation jobs. Comparison jobs match a LEFT source (the system of record for that money
 * movement) against a RIGHT source by a shared reference; the names say what each kind of
 * difference means.
 */
public enum ReconJob {

    /** Payment-gateway charges vs wallet ADD_MONEY journals (reference = provider reference). */
    PG_VS_LEDGER("CHARGED_NOT_CREDITED", "CREDITED_WITHOUT_CHARGE"),
    /** Completed transfers vs UPI switch settlement records (reference = transaction id). */
    TRANSFERS_VS_UPI("MISSING_SWITCH_RECORD", "ORPHAN_SWITCH_RECORD"),
    /** Wallet balances vs the sum of their ledger entries, and journal balance. */
    WALLET_INTEGRITY(null, null);

    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private final String leftOnly;
    private final String rightOnly;

    ReconJob(String leftOnly, String rightOnly) {
        this.leftOnly = leftOnly;
        this.rightOnly = rightOnly;
    }

    public boolean isComparison() {
        return leftOnly != null;
    }

    public String leftOnlyType() {
        return leftOnly;
    }

    public String rightOnlyType() {
        return rightOnly;
    }
}
