package com.payverse.user.domain;

import java.util.concurrent.ThreadLocalRandom;

/** Generates checksum-valid Aadhaar numbers for tests. */
public final class TestAadhaar {

    private TestAadhaar() {
    }

    public static String random() {
        String body;
        do {
            body = (2 + ThreadLocalRandom.current().nextInt(8)) + String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
        } while (body.contains("9999"));
        String full = Verhoeff.withCheckDigit(body);
        return full.contains("9999") ? random() : full;
    }

    public static String withMarker() {
        return Verhoeff.withCheckDigit("29999" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)));
    }
}
