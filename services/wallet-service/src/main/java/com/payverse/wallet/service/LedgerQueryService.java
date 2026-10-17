package com.payverse.wallet.service;

import com.payverse.common.money.Money;
import com.payverse.wallet.dto.IntegrityReport;
import com.payverse.wallet.dto.LedgerEntryResponse;
import com.payverse.wallet.dto.MoneyDto;
import com.payverse.wallet.dto.PageResponse;
import com.payverse.wallet.entity.Journal;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.model.JournalSummary;
import com.payverse.wallet.entity.LedgerEntry;
import com.payverse.wallet.entity.SystemAccount;
import com.payverse.wallet.entity.Wallet;
import com.payverse.wallet.mapper.WalletMapper;
import com.payverse.wallet.repository.JournalRepository;
import com.payverse.wallet.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Ledger history for users and the ledger integrity check (also run nightly). */
@Service
public class LedgerQueryService {

    private static final Logger log = LoggerFactory.getLogger(LedgerQueryService.class);

    private final WalletService walletService;
    private final LedgerEntryRepository entries;
    private final JournalRepository journals;
    private final WalletMapper mapper;
    private final Clock clock;

    public LedgerQueryService(WalletService walletService, LedgerEntryRepository entries, JournalRepository journals,
                              WalletMapper mapper, Clock clock) {
        this.walletService = walletService;
        this.entries = entries;
        this.journals = journals;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Journals of one type in [from, to) for reconciliation. */
    @Transactional(readOnly = true)
    public Page<JournalSummary> journals(JournalType type, Instant from, Instant to, int page, int size) {
        return journals.findSummaries(type, from, to, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> history(UUID userId, Instant from, Instant to, int page, int size) {
        Wallet wallet = walletService.get(userId);
        Page<LedgerEntry> result = entries.findByAccountIdAndCreatedAtBetween(wallet.getLedgerAccountId(),
                from == null ? Instant.EPOCH : from, to == null ? clock.instant().plusSeconds(60) : to,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, Journal> byId = journals.findAllById(result.map(LedgerEntry::getJournalId).toList()).stream()
                .collect(Collectors.toMap(Journal::getId, Function.identity()));
        List<LedgerEntryResponse> items = result.stream().map(e -> mapper.toResponse(e, byId.get(e.getJournalId()))).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public IntegrityReport checkIntegrity() {
        List<UUID> outOfBalance = entries.findWalletsOutOfBalance();
        List<UUID> unbalanced = entries.findUnbalancedJournals();
        // suspense is a liability: credits − debits = money currently in flight
        Money suspense = Money.inr(entries.netCredit(SystemAccount.TRANSFER_SUSPENSE.id()));
        return new IntegrityReport(outOfBalance.isEmpty() && unbalanced.isEmpty(), outOfBalance, unbalanced,
                MoneyDto.of(suspense), clock.instant());
    }

    /** Nightly self-check (02:30 IST); reconciliation-service does the cross-system check in Phase 7. */
    @Scheduled(cron = "${payverse.wallet.integrity-cron:0 30 2 * * *}", zone = "Asia/Kolkata")
    public void nightlyIntegrityCheck() {
        IntegrityReport report = checkIntegrity();
        if (report.healthy()) {
            log.info("Ledger integrity check passed (suspense {})", report.transferSuspense().amount());
        } else {
            log.error("LEDGER INTEGRITY VIOLATION: wallets out of balance {}, unbalanced journals {}",
                    report.walletsOutOfBalance(), report.unbalancedJournals());
        }
    }
}
