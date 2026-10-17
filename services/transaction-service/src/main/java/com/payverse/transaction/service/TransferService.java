package com.payverse.transaction.service;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.transaction.client.AuthClient;
import com.payverse.transaction.client.UserClient;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatusHistory;
import com.payverse.transaction.entity.TransferSource;
import com.payverse.transaction.exception.TransactionErrorCode;
import com.payverse.transaction.repository.TransactionRepository;
import com.payverse.transaction.repository.TransactionStatusHistoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Starts transfers. Idempotency is durable at the transaction level: {@code UNIQUE(payer, key)} —
 * a retry with the same Idempotency-Key returns the existing transaction (and a different body with
 * the same key is rejected), so no retry can ever create a second transfer.
 * <p>
 * Deliberately <b>not</b> {@code @Transactional}: the saga commits each step on its own.
 */
@Service
public class TransferService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_:.-]{8,100}");

    public record TransferCommand(UUID payerUserId, PayeeResolver.Target payee, Money amount, String note, TransferSource source,
                                  UUID sourceId, String pin, String idempotencyKey) {

        @Override
        public String toString() {
            return "TransferCommand[payer=" + payerUserId + ", payee=" + payee.type() + ", amount=" + amount + ", pin=****]";
        }
    }

    public record TransferResult(Transaction transaction, boolean replayed) {
    }

    private final TransactionRepository transactions;
    private final TransactionStatusHistoryRepository history;
    private final PayeeResolver payeeResolver;
    private final AuthClient authClient;
    private final UserClient userClient;
    private final TransferSaga saga;
    private final TransactionEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TransferService(TransactionRepository transactions, TransactionStatusHistoryRepository history, PayeeResolver payeeResolver,
                           AuthClient authClient, UserClient userClient, TransferSaga saga, TransactionEventPublisher events,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = transactions;
        this.history = history;
        this.payeeResolver = payeeResolver;
        this.authClient = authClient;
        this.userClient = userClient;
        this.saga = saga;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public TransferResult transfer(TransferCommand command) {
        String key = command.idempotencyKey();
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new PayverseException(key == null ? CommonErrorCode.IDEMPOTENCY_KEY_MISSING : CommonErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 8-100 characters of [A-Za-z0-9_:.-]");
        }
        String hash = requestHash(command);
        var existing = transactions.findByPayerUserIdAndIdempotencyKey(command.payerUserId(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        if (command.pin() == null || command.pin().isBlank()) {
            throw new PayverseException(TransactionErrorCode.TXN_PIN_REQUIRED, null);
        }
        authClient.verifyPin(command.payerUserId(), command.pin());
        PayeeResolver.Resolution resolution = payeeResolver.resolve(command.payee(), command.amount());
        String payerRef = userClient.summary(command.payerUserId()).map(UserClient.UserSummary::display).orElse("PayVerse user");

        Transaction created;
        try {
            created = tx.execute(status -> {
                Transaction txn = transactions.saveAndFlush(Transaction.initiate(command.payerUserId(), payerRef, resolution.payee(),
                        resolution.amount(), command.note(), command.source(), command.sourceId(), key, hash, clock.instant()));
                history.save(TransactionStatusHistory.created(txn));
                events.initiated(txn);
                return txn;
            });
        } catch (DataIntegrityViolationException raced) {
            // a concurrent duplicate with the same key won the insert
            return replay(transactions.findByPayerUserIdAndIdempotencyKey(command.payerUserId(), key).orElseThrow(() -> raced), hash);
        }
        saga.run(created.getId());
        return new TransferResult(transactions.findById(created.getId()).orElseThrow(), false);
    }

    private TransferResult replay(Transaction existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, null);
        }
        if (!existing.getStatus().isSettled()) {
            saga.run(existing.getId()); // a retry nudges an unfinished saga along
        }
        return new TransferResult(transactions.findById(existing.getId()).orElseThrow(), true);
    }

    static String requestHash(TransferCommand c) {
        String canonical = String.join("|", String.valueOf(c.payee().type()), String.valueOf(c.payee().value()),
                c.amount() == null ? "" : c.amount().toPlainString(), c.note() == null ? "" : c.note(),
                String.valueOf(c.source()), String.valueOf(c.sourceId()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
