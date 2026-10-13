package com.payverse.wallet.repository;

import com.payverse.wallet.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByJournalId(UUID journalId);

    Page<LedgerEntry> findByAccountIdAndCreatedAtBetween(UUID accountId, Instant from, Instant to, Pageable pageable);

    /** Credits − debits for one account: the ledger-derived balance. */
    @Query("""
            select coalesce(sum(case when e.side = com.payverse.wallet.entity.EntrySide.CREDIT then e.amount else -e.amount end), 0)
            from LedgerEntry e where e.accountId = :accountId
            """)
    BigDecimal netCredit(@Param("accountId") UUID accountId);

    /** Ids of wallets whose materialised balance differs from the sum of their ledger entries. */
    @Query(value = """
            SELECT w.id FROM wallets w
            LEFT JOIN ledger_entries e ON e.account_id = w.ledger_account_id
            GROUP BY w.id, w.balance
            HAVING w.balance <> COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            """, nativeQuery = true)
    List<UUID> findWalletsOutOfBalance();

    /** Ids of journals whose debits and credits differ (must always be empty). */
    @Query(value = """
            SELECT journal_id FROM ledger_entries GROUP BY journal_id
            HAVING SUM(amount) FILTER (WHERE side = 'DEBIT') IS DISTINCT FROM SUM(amount) FILTER (WHERE side = 'CREDIT')
            """, nativeQuery = true)
    List<UUID> findUnbalancedJournals();
}
