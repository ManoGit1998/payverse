package com.payverse.payment.dto;

/** Confirmation data before paying a VPA (no internal user ids). */
public record ResolvedVpaResponse(String vpa, String payeeName) {
}
