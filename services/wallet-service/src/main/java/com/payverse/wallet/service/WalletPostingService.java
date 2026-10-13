package com.payverse.wallet.service;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.wallet.config.WalletProperties;
import com.payverse.wallet.dto.PostingResult;
import com.payverse.wallet.entity.EntrySide;
import com.payverse.wallet.entity.Journal;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.entity.LedgerEntry;
import com.payverse.wallet.entity.SystemAccount;
import com.payverse.wallet.entity.Wallet;
import com.payverse.wallet.exception.WalletErrorCode;
import com.payverse.wallet.repository.JournalRepository;
import com.payverse.wallet.repository.LedgerEntryRepository;
import com.payverse.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only place that writes to the ledger. Each posting, in one DB transaction:
 * <ol>
 *   <li>takes the wallet's distributed lock (held until commit/rollback);</li>
 *   <li>returns the earlier result if the idempotency key was already applied;</li>
 *   <li>checks status and KYC-tier limits;</li>
 *   <li>writes a balanced journal (one wallet entry + one contra entry) and updates the materialised
 *       balance ({@code @Version} + {@code CHECK balance >= 0} as safety nets);</li>
 *   <li>publishes {@code wallet.ledger-posted} via the outbox and writes the audit log.</li>
 * </ol>
 */
@Service
public class WalletPostingService {

    public enum Direction { DEBIT, CREDIT }

    /**
     * @param idempotencyKey globally unique key for this posting (callers prefix it with their scope)
     * @param actor          user id or service client id, for the audit log
     */
    public record PostingCommand(UUID userId, Direction direction, Money amount, SystemAccount contraAccount, JournalType type,
                                 LimitPolicy limitPolicy, String idempotencyKey, String referenceType, String referenceId,
                                 String description, String actor) {
    }

    private final WalletRepository wallets;
    private final JournalRepository journals;
    private final LedgerEntryRepository entries;
    private final WalletLock walletLock;
    private final LimitService limitService;
    private final WalletEventPublisher events;
    private final AuditLogger audit;
    private final BalanceCache cache;
    private final WalletProperties properties;
    private final Clock clock;

    public WalletPostingService(WalletRepository wallets, JournalRepository journals, LedgerEntryRepository entries,
                                WalletLock walletLock, LimitService limitService, WalletEventPublisher events,
                                AuditLogger audit, BalanceCache cache, WalletProperties properties, Clock clock) {
        this.wallets = wallets;
        this.journals = journals;
        this.entries = entries;
        this.walletLock = walletLock;
        this.limitService = limitService;
        this.events = events;
        this.audit = audit;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public PostingResult post(PostingCommand command) {
        validateAmount(command.amount());
        Wallet wallet = lockedWallet(command.userId());

        var existing = journals.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), wallet, command);
        }
        if (command.limitPolicy() != LimitPolicy.NONE) {
            wallet.ensureActive();
        }
        limitService.apply(wallet, command.amount(), command.limitPolicy());

        Instant now = clock.instant();
        if (command.direction() == Direction.DEBIT) {
            wallet.applyDebit(command.amount(), now);
        } else {
            wallet.applyCredit(command.amount(), now);
        }
        Journal journal = journals.save(Journal.create(command.type(), command.idempotencyKey(), command.referenceType(),
                command.referenceId(), command.description(), null, now));
        EntrySide walletSide = command.direction() == Direction.DEBIT ? EntrySide.DEBIT : EntrySide.CREDIT;
        List<LedgerEntry> written = entries.saveAll(List.of(
                LedgerEntry.of(journal.getId(), wallet.getLedgerAccountId(), walletSide, command.amount(), wallet.getBalanceMoney(), now),
                LedgerEntry.of(journal.getId(), command.contraAccount().id(), opposite(walletSide), command.amount(), null, now)));
        wallets.saveAndFlush(wallet);

        events.ledgerPosted(journal, written, wallet);
        audit.record(command.actor(), "WALLET_" + command.direction(), "Wallet", wallet.getId().toString(), Map.of(
                "journalId", journal.getId().toString(), "type", command.type().name(), "amount", command.amount().toPlainString(),
                "balanceAfter", wallet.getBalanceMoney().toPlainString(),
                "reference", String.valueOf(command.referenceType()) + ":" + command.referenceId()));
        cache.evictAfterCommit(wallet.getUserId());
        return new PostingResult(journal.getId(), wallet.getId(), wallet.getUserId(), wallet.getBalanceMoney(), false, now);
    }

    /**
     * Compensates a journal by posting its mirror image (saga compensation, refunds). Allowed on frozen
     * wallets and exempt from limits; each journal can be reversed once.
     */
    @Transactional
    public PostingResult reverse(UUID userId, UUID journalId, String idempotencyKey, String reason, String actor) {
        Wallet wallet = lockedWallet(userId);
        var byKey = journals.findByIdempotencyKey(idempotencyKey);
        if (byKey.isPresent()) {
            return replayed(byKey.get(), wallet);
        }
        var alreadyReversed = journals.findByReversesJournalId(journalId);
        if (alreadyReversed.isPresent()) {
            return replayed(alreadyReversed.get(), wallet);
        }
        Journal original = journals.findById(journalId)
                .orElseThrow(() -> new PayverseException(WalletErrorCode.WALLET_JOURNAL_NOT_FOUND, null));
        List<LedgerEntry> originalEntries = entries.findByJournalId(journalId);
        LedgerEntry walletEntry = originalEntries.stream()
                .filter(e -> e.getAccountId().equals(wallet.getLedgerAccountId()))
                .findFirst()
                .orElseThrow(() -> new PayverseException(WalletErrorCode.WALLET_INVALID_REVERSAL,
                        "Journal does not belong to this wallet"));
        if (original.getType() == JournalType.REVERSAL) {
            throw new PayverseException(WalletErrorCode.WALLET_INVALID_REVERSAL, "A reversal cannot be reversed");
        }

        Instant now = clock.instant();
        Money amount = walletEntry.getAmountMoney();
        if (walletEntry.getSide() == EntrySide.DEBIT) {
            wallet.applyCredit(amount, now);
        } else {
            wallet.applyDebit(amount, now);
        }
        Journal reversal = journals.save(Journal.create(JournalType.REVERSAL, idempotencyKey, "Journal", journalId.toString(),
                reason == null ? "Reversal of " + original.getType() : reason, journalId, now));
        List<LedgerEntry> mirrored = new ArrayList<>();
        for (LedgerEntry entry : originalEntries) {
            boolean isWallet = entry.getAccountId().equals(wallet.getLedgerAccountId());
            mirrored.add(LedgerEntry.of(reversal.getId(), entry.getAccountId(), opposite(entry.getSide()), entry.getAmountMoney(),
                    isWallet ? wallet.getBalanceMoney() : null, now));
        }
        List<LedgerEntry> written = entries.saveAll(mirrored);
        wallets.saveAndFlush(wallet);

        events.ledgerPosted(reversal, written, wallet);
        audit.record(actor, "WALLET_REVERSAL", "Wallet", wallet.getId().toString(), Map.of(
                "journalId", reversal.getId().toString(), "reverses", journalId.toString(), "amount", amount.toPlainString(),
                "balanceAfter", wallet.getBalanceMoney().toPlainString()));
        cache.evictAfterCommit(wallet.getUserId());
        return new PostingResult(reversal.getId(), wallet.getId(), wallet.getUserId(), wallet.getBalanceMoney(), false, now);
    }

    /** Checks status and limits under the wallet lock without posting (e.g. before charging a gateway). */
    @Transactional
    public void preflight(UUID userId, Money amount, LimitPolicy policy) {
        validateAmount(amount);
        Wallet wallet = lockedWallet(userId);
        wallet.ensureActive();
        if (policy == LimitPolicy.DEBIT && wallet.getBalanceMoney().isLessThan(amount)) {
            throw new PayverseException(WalletErrorCode.WALLET_INSUFFICIENT_BALANCE, null,
                    Map.of("balance", wallet.getBalanceMoney().toPlainString(), "requested", amount.toPlainString()));
        }
        limitService.check(wallet, amount, policy);
    }

    private Wallet lockedWallet(UUID userId) {
        UUID walletId = wallets.findIdByUserId(userId)
                .orElseThrow(() -> new PayverseException(WalletErrorCode.WALLET_NOT_FOUND, null));
        walletLock.lockForTransaction(walletId);
        // loaded only after the lock is held, so the balance we read is current
        return wallets.findById(walletId).orElseThrow();
    }

    private PostingResult replay(Journal journal, Wallet wallet, PostingCommand command) {
        LedgerEntry walletEntry = entries.findByJournalId(journal.getId()).stream()
                .filter(e -> e.getAccountId().equals(wallet.getLedgerAccountId()))
                .findFirst()
                .orElseThrow(() -> new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, null));
        EntrySide expectedSide = command.direction() == Direction.DEBIT ? EntrySide.DEBIT : EntrySide.CREDIT;
        if (walletEntry.getSide() != expectedSide || !walletEntry.getAmountMoney().equals(command.amount())
                || journal.getType() != command.type()) {
            throw new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, null);
        }
        return new PostingResult(journal.getId(), wallet.getId(), wallet.getUserId(), Money.inr(walletEntry.getBalanceAfter()),
                true, journal.getCreatedAt());
    }

    private PostingResult replayed(Journal journal, Wallet wallet) {
        LedgerEntry walletEntry = entries.findByJournalId(journal.getId()).stream()
                .filter(e -> e.getAccountId().equals(wallet.getLedgerAccountId()))
                .findFirst()
                .orElseThrow(() -> new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, null));
        return new PostingResult(journal.getId(), wallet.getId(), wallet.getUserId(), Money.inr(walletEntry.getBalanceAfter()),
                true, journal.getCreatedAt());
    }

    private void validateAmount(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new PayverseException(WalletErrorCode.WALLET_INVALID_AMOUNT, "Amount must be positive");
        }
        if (amount.isGreaterThan(Money.inr(properties.maxTransactionAmount()))) {
            throw new PayverseException(WalletErrorCode.WALLET_INVALID_AMOUNT,
                    "Maximum per transaction is " + Money.inr(properties.maxTransactionAmount()).toPlainString());
        }
    }

    private static EntrySide opposite(EntrySide side) {
        return side == EntrySide.DEBIT ? EntrySide.CREDIT : EntrySide.DEBIT;
    }
}
