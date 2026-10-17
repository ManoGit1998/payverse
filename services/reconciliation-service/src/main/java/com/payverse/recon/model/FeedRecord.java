package com.payverse.recon.model;

import java.math.BigDecimal;
import java.time.Instant;

/** One record from a source system, keyed by the reference both sides share. */
public record FeedRecord(String reference, BigDecimal amount, Instant occurredAt) {
}
