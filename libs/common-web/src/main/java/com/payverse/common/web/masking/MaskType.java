package com.payverse.common.web.masking;

/** Kinds of PII masking. */
public enum MaskType {
    /** {@code 9876543210} → {@code ******3210} */
    MOBILE,
    /** {@code 123456789012} → {@code ********9012} */
    ACCOUNT_NUMBER,
    /** {@code ravi.kumar@example.com} → {@code r*********@example.com} */
    EMAIL,
    /** {@code ABCDE1234F} → {@code ******234F}; generic ids (PAN, Aadhaar, KYC ids) */
    ID,
    /** Fully hidden: {@code ****} */
    FULL
}
