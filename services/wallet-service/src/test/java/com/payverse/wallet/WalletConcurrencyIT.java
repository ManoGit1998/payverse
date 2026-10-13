package com.payverse.wallet;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.wallet.dto.IntegrityReport;
import com.payverse.wallet.dto.PostingResult;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.entity.KycTier;
import com.payverse.wallet.entity.SystemAccount;
import com.payverse.wallet.exception.WalletErrorCode;
import com.payverse.wallet.service.LedgerQueryService;
import com.payverse.wallet.service.LimitPolicy;
import com.payverse.wallet.service.WalletPostingService;
import com.payverse.wallet.service.WalletPostingService.Direction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the ledger stays exact under concurrency: the per-wallet Redis lock (held until commit),
 * {@code @Version} and {@code CHECK (balance >= 0)} together must never lose an update or overdraw.
 */
class WalletConcurrencyIT extends WalletIntegrationTest {

    @Autowired
    WalletPostingService postings;

    @Autowired
    LedgerQueryService ledger;

    private WalletPostingService.PostingCommand command(UUID user, Direction direction, Money amount, String key, String txn) {
        return new WalletPostingService.PostingCommand(user, direction, amount, SystemAccount.TRANSFER_SUSPENSE,
                direction == Direction.DEBIT ? JournalType.TRANSFER_DEBIT : JournalType.TRANSFER_CREDIT,
                direction == Direction.DEBIT ? LimitPolicy.DEBIT : LimitPolicy.CREDIT, key, "Transaction", txn, "concurrency test", "it");
    }

    /** 100 P2P transfers between 4 wallets, run in parallel exactly like the transaction saga will. */
    @Test
    void hundredParallelTransfersKeepEveryBalanceExact() throws Exception {
        List<UUID> wallets = List.of(
                fundedWallet(KycTier.FULL_KYC, "10000"), fundedWallet(KycTier.FULL_KYC, "10000"),
                fundedWallet(KycTier.FULL_KYC, "10000"), fundedWallet(KycTier.FULL_KYC, "10000"));
        Money suspenseBefore = suspense();
        Map<UUID, Money> expectedDelta = new ConcurrentHashMap<>();
        wallets.forEach(w -> expectedDelta.put(w, Money.zeroInr()));
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger compensated = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    UUID from = wallets.get(random.nextInt(4));
                    UUID to;
                    do {
                        to = wallets.get(random.nextInt(4));
                    } while (to.equals(from));
                    Money amount = Money.ofMinor(random.nextLong(100, 50_000), Money.INR); // ₹1.00 – ₹500.00
                    String txn = UUID.randomUUID().toString();
                    start.await();

                    PostingResult debit = postings.post(command(from, Direction.DEBIT, amount, txn + ":debit", txn));
                    try {
                        postings.post(command(to, Direction.CREDIT, amount, txn + ":credit", txn));
                        expectedDelta.merge(from, amount.negate(), Money::plus);
                        expectedDelta.merge(to, amount, Money::plus);
                        completed.incrementAndGet();
                    } catch (PayverseException e) {
                        postings.reverse(from, debit.journalId(), txn + ":compensate", "credit failed", "it");
                        compensated.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(completed.get() + compensated.get()).isEqualTo(100);
        Money total = Money.zeroInr();
        for (UUID wallet : wallets) {
            Money expected = Money.inr("10000").plus(expectedDelta.get(wallet));
            assertThat(balance(wallet)).as("balance of %s", wallet).isEqualTo(expected);
            assertThat(ledgerBalance(wallet)).as("ledger sum of %s", wallet).isEqualTo(expected);
            total = total.plus(balance(wallet));
        }
        assertThat(total).isEqualTo(Money.inr("40000"));
        assertThat(journalCount(wallets)).isEqualTo(4 + 2L * completed.get() + 2L * compensated.get());

        IntegrityReport report = ledger.checkIntegrity();
        assertThat(report.healthy()).isTrue();
        // every transfer either completed or was compensated, so nothing is left in flight
        assertThat(suspense()).isEqualTo(suspenseBefore);
    }

    /** 100 concurrent ₹100 debits against ₹5,000: exactly 50 succeed and the wallet ends at exactly ₹0. */
    @Test
    void parallelDebitsNeverOverdraw() throws Exception {
        UUID wallet = fundedWallet(KycTier.FULL_KYC, "5000");
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> {
                    String txn = UUID.randomUUID().toString();
                    start.await();
                    try {
                        postings.post(command(wallet, Direction.DEBIT, Money.inr("100"), txn, txn));
                        succeeded.incrementAndGet();
                    } catch (PayverseException e) {
                        assertThat(e.errorCode()).isEqualTo(WalletErrorCode.WALLET_INSUFFICIENT_BALANCE);
                        insufficient.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(succeeded).hasValue(50);
        assertThat(insufficient).hasValue(50);
        assertThat(balance(wallet)).isEqualTo(Money.zeroInr());
        assertThat(ledgerBalance(wallet)).isEqualTo(Money.zeroInr());
    }

    /** A saga step retried 30 times concurrently (same Idempotency-Key) moves money exactly once. */
    @Test
    void concurrentRetriesOfOneStepApplyOnce() throws Exception {
        UUID wallet = fundedWallet(KycTier.FULL_KYC, "0");
        String txn = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        Map<Boolean, AtomicInteger> byReplay = new HashMap<>(Map.of(true, new AtomicInteger(), false, new AtomicInteger()));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    PostingResult result = postings.post(command(wallet, Direction.CREDIT, Money.inr("250.75"), txn + ":credit", txn));
                    byReplay.get(result.replayed()).incrementAndGet();
                    return result.journalId();
                }));
            }
            start.countDown();
            List<UUID> journalIds = new ArrayList<>();
            for (Future<UUID> f : futures) {
                journalIds.add(f.get());
            }
            assertThat(journalIds).containsOnly(journalIds.getFirst());
        }

        assertThat(byReplay.get(false)).hasValue(1);
        assertThat(byReplay.get(true)).hasValue(29);
        assertThat(balance(wallet)).isEqualTo(Money.inr("250.75"));
    }

    /** Net credit of TRANSFER_SUSPENSE (shared by all tests in this database, so compare deltas). */
    private Money suspense() {
        return Money.inr(ledger.checkIntegrity().transferSuspense().amount());
    }

    private Money ledgerBalance(UUID user) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                FROM ledger_entries e JOIN wallets w ON w.ledger_account_id = e.account_id WHERE w.user_id = ?
                """, BigDecimal.class, user);
        return Money.inr(sum);
    }

    private long journalCount(List<UUID> users) {
        long count = 0;
        for (UUID user : users) {
            count += jdbc.queryForObject("""
                    SELECT count(*) FROM ledger_entries e JOIN wallets w ON w.ledger_account_id = e.account_id WHERE w.user_id = ?
                    """, Long.class, user);
        }
        return count;
    }
}
