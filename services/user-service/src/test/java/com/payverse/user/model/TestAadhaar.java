package com.payverse.user.model;

import java.util.concurrent.ThreadLocalRandom;

/** Generates checksum-valid Aadhaar numbers for tests (never containing the MOCK reject marker 9999). */
public final class TestAadhaar {

    private TestAadhaar() {
    }

    public static String random() {
        while (true) {
            String body = (2 + ThreadLocalRandom.current().nextInt(8))
                    + String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
            String full = Verhoeff.withCheckDigit(body);
            if (!full.contains("9999")) {
                return full;
            }
        }
    }
}
