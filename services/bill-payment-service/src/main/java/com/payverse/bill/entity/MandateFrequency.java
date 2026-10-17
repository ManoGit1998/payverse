package com.payverse.bill.entity;

import java.time.Instant;
import java.time.ZoneId;

/** Runs are scheduled in IST calendar terms (a monthly mandate on the 31st runs on the last day of shorter months). */
public enum MandateFrequency {
    WEEKLY, MONTHLY;

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public Instant next(Instant from) {
        var local = from.atZone(IST);
        return (this == WEEKLY ? local.plusWeeks(1) : local.plusMonths(1)).toInstant();
    }
}
