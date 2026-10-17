package com.payverse.bill.service;

import com.payverse.bill.client.AuthClient;
import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.entity.BillPayment;
import com.payverse.bill.entity.Biller;
import com.payverse.bill.entity.PaymentSource;
import com.payverse.bill.exception.BillErrorCode;
import com.payverse.bill.repository.BillPaymentRepository;
import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.masking.PiiMasker;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pays bills. Idempotency is durable at the row level: {@code UNIQUE(user_id, idempotency_key)}
 * plus a request hash, so a retry returns the same payment and nudges it forward if unfinished.
 * Deliberately not {@code @Transactional}: the processor commits each step on its own.
 */
@Service
public class BillPaymentService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_:.-]{8,100}");

    public record PayCommand(UUID userId, String billerCode, String accountRef, Money amount, String pin, String idempotencyKey,
                             PaymentSource source, UUID mandateId) {

        @Override
        public String toString() {
            return "PayCommand[user=" + userId + ", biller=" + billerCode + ", amount=" + amount + ", pin=****]";
        }
    }

    public record PayResult(BillPayment payment, boolean replayed) {
    }

    private final BillPaymentRepository payments;
    private final BillerService billers;
    private final AuthClient auth;
    private final BillPaymentProcessor processor;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BillPaymentService(BillPaymentRepository payments, BillerService billers, AuthClient auth, BillPaymentProcessor processor,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.payments = payments;
        this.billers = billers;
        this.auth = auth;
        this.processor = processor;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public PayResult pay(PayCommand c) {
        String key = c.idempotencyKey();
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new PayverseException(key == null ? CommonErrorCode.IDEMPOTENCY_KEY_MISSING : CommonErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 8-100 characters of [A-Za-z0-9_:.-]");
        }
        Biller biller = billers.get(c.billerCode());
        String account = billers.validAccount(biller, c.accountRef());
        String hash = hash(biller.getCode(), account, c.amount());
        var existing = payments.findByUserIdAndIdempotencyKey(c.userId(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        if (!biller.acceptsAmount(c.amount())) {
            throw new PayverseException(BillErrorCode.BILL_AMOUNT_OUT_OF_RANGE,
                    "Amount must be between INR " + biller.getMinAmount() + " and INR " + biller.getMaxAmount());
        }
        if (c.source() == PaymentSource.DIRECT) {
            if (c.pin() == null || c.pin().isBlank()) {
                throw new PayverseException(BillErrorCode.BILL_PIN_REQUIRED, null);
            }
            auth.verifyPin(c.userId(), c.pin());
        }
        BillPayment created;
        try {
            created = tx.execute(status -> payments.saveAndFlush(BillPayment.create(c.userId(), biller, account,
                    PiiMasker.maskAccountNumber(account), c.amount(), c.source(), c.mandateId(), key, hash, clock.instant())));
        } catch (DataIntegrityViolationException raced) {
            return replay(payments.findByUserIdAndIdempotencyKey(c.userId(), key).orElseThrow(() -> raced), hash);
        }
        processor.run(created.getId());
        return new PayResult(payments.findById(created.getId()).orElseThrow(), false);
    }

    private PayResult replay(BillPayment existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, null);
        }
        if (!existing.isSettled()) {
            processor.run(existing.getId());
        }
        return new PayResult(payments.findById(existing.getId()).orElseThrow(), true);
    }

    @Transactional(readOnly = true)
    public BillPayment get(UUID userId, UUID id) {
        return payments.findById(id).filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new PayverseException(BillErrorCode.BILL_NOT_FOUND, null));
    }

    @Transactional(readOnly = true)
    public Page<BillPayment> history(UUID userId, BillCategory category, int page, int size) {
        return category == null ? payments.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                : payments.findByUserIdAndCategoryOrderByCreatedAtDesc(userId, category, PageRequest.of(page, size));
    }

    static String hash(String billerCode, String account, Money amount) {
        String canonical = billerCode + "|" + account + "|" + (amount == null ? "" : amount.amount().toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
