package com.payverse.bill.service;

import com.payverse.bill.entity.Biller;
import com.payverse.bill.model.FetchedBill;
import com.payverse.common.money.Money;
import com.payverse.common.web.masking.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MOCK biller aggregator. Bills are deterministic per account and month (INR 150–4,149.xx, due in
 * 10 days). Test hooks: accounts ending in {@code 0000} don't exist; amounts ending in {@code .13}
 * are declined (→ refund); amounts ending in {@code .14} time out on the first attempt only.
 */
@Component
public class MockBillerAdapter implements BillerGateway {

    private static final Logger log = LoggerFactory.getLogger(MockBillerAdapter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ConcurrentMap<UUID, Boolean> timedOutOnce = new ConcurrentHashMap<>();
    private final Clock clock;

    public MockBillerAdapter(Clock clock) {
        this.clock = clock;
        log.warn("MockBillerAdapter (MOCK) is active: bills are simulated and no biller is paid.");
    }

    @Override
    public FetchedBill fetch(Biller biller, String accountRef) {
        if (accountRef.endsWith("0000")) {
            throw new AccountNotFoundException("No " + biller.getAccountLabel().toLowerCase() + " " + PiiMasker.maskAccountNumber(accountRef));
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        byte[] digest = sha256(biller.getCode() + "|" + accountRef + "|" + YearMonth.from(today));
        long paise = 15_000 + Math.floorMod(((digest[0] & 0xffL) << 16) | ((digest[1] & 0xffL) << 8) | (digest[2] & 0xffL), 400_000);
        String billNumber = "BILL-" + HexFormat.of().formatHex(digest, 3, 8).toUpperCase();
        return new FetchedBill(biller.getCode(), PiiMasker.maskAccountNumber(accountRef), "Customer " + PiiMasker.maskAccountNumber(accountRef),
                billNumber, Money.inr(BigDecimal.valueOf(paise, 2)), today.plusDays(10));
    }

    @Override
    public String pay(Biller biller, String accountRef, Money amount, UUID paymentId) {
        String cents = amount.amount().toPlainString();
        if (cents.endsWith(".13")) {
            throw new BillerDeclinedException("MOCK biller declined amounts ending in .13");
        }
        if (cents.endsWith(".14") && timedOutOnce.putIfAbsent(paymentId, Boolean.TRUE) == null) {
            throw new BillerUnavailableException("MOCK biller timed out (first attempt for .14)");
        }
        // deterministic per payment id = idempotent
        return "MOCKBBPS-" + HexFormat.of().formatHex(sha256(paymentId.toString()), 0, 6).toUpperCase();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
