package com.payverse.wallet.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.wallet.dto.WalletResponse;
import com.payverse.wallet.entity.KycTier;
import com.payverse.wallet.entity.LedgerAccount;
import com.payverse.wallet.entity.Wallet;
import com.payverse.wallet.exception.WalletErrorCode;
import com.payverse.wallet.mapper.WalletMapper;
import com.payverse.wallet.repository.LedgerAccountRepository;
import com.payverse.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Wallet lifecycle: creation (event or lazy), summary, KYC tier sync, freeze/unfreeze. */
@Service
public class WalletService {

    private final WalletRepository wallets;
    private final LedgerAccountRepository accounts;
    private final WalletLock walletLock;
    private final LimitService limitService;
    private final WalletEventPublisher events;
    private final BalanceCache cache;
    private final AuditLogger audit;
    private final WalletMapper mapper;
    private final Clock clock;

    public WalletService(WalletRepository wallets, LedgerAccountRepository accounts, WalletLock walletLock,
                         LimitService limitService, WalletEventPublisher events, BalanceCache cache, AuditLogger audit,
                         WalletMapper mapper, Clock clock) {
        this.wallets = wallets;
        this.accounts = accounts;
        this.walletLock = walletLock;
        this.limitService = limitService;
        this.events = events;
        this.cache = cache;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Idempotent: returns the existing wallet if the user already has one. */
    @Transactional
    public Wallet getOrCreate(UUID userId) {
        var existing = wallets.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        walletLock.lockForTransaction("wallet-create:" + userId);
        return wallets.findByUserId(userId).orElseGet(() -> {
            Instant now = clock.instant();
            UUID walletId = UUID.randomUUID();
            LedgerAccount account = accounts.save(LedgerAccount.forWallet(walletId, userId, now));
            Wallet wallet = wallets.saveAndFlush(Wallet.open(walletId, userId, account.getId(), KycTier.NONE, now));
            events.walletCreated(wallet);
            return wallet;
        });
    }

    /** Wallet summary with limits; served from Redis when cached. */
    @Transactional
    public WalletResponse summary(UUID userId) {
        return cache.get(userId).orElseGet(() -> {
            Wallet wallet = getOrCreate(userId);
            WalletResponse response = mapper.toResponse(wallet, limitService.view(wallet));
            cache.put(response);
            return response;
        });
    }

    @Transactional(readOnly = true)
    public Wallet get(UUID userId) {
        return wallets.findByUserId(userId).orElseThrow(() -> new PayverseException(WalletErrorCode.WALLET_NOT_FOUND, null));
    }

    /** Handles {@code user.kyc-upgraded}. Creates the wallet first if the registration event has not arrived yet. */
    @Transactional
    public void changeTier(UUID userId, KycTier tier) {
        Wallet wallet = getOrCreate(userId);
        walletLock.lockForTransaction(wallet.getId());
        Wallet locked = wallets.findById(wallet.getId()).orElseThrow();
        if (locked.changeTier(tier, clock.instant())) {
            audit.record("user-service", "WALLET_TIER_CHANGED", "Wallet", locked.getId().toString(), Map.of("tier", tier.name()));
            cache.evictAfterCommit(userId);
        }
    }

    @Transactional
    public Wallet freeze(UUID userId, String reason, String actor) {
        Wallet wallet = lockedExisting(userId);
        if (wallet.freeze(reason, clock.instant())) {
            wallets.saveAndFlush(wallet);
            events.statusChanged(wallet, actor);
            audit.record(actor, "WALLET_FROZEN", "Wallet", wallet.getId().toString(), Map.of("reason", reason));
            cache.evictAfterCommit(userId);
        }
        return wallet;
    }

    @Transactional
    public Wallet unfreeze(UUID userId, String actor) {
        Wallet wallet = lockedExisting(userId);
        if (wallet.unfreeze(clock.instant())) {
            wallets.saveAndFlush(wallet);
            events.statusChanged(wallet, actor);
            audit.record(actor, "WALLET_UNFROZEN", "Wallet", wallet.getId().toString(), Map.of());
            cache.evictAfterCommit(userId);
        }
        return wallet;
    }

    private Wallet lockedExisting(UUID userId) {
        UUID walletId = wallets.findIdByUserId(userId)
                .orElseThrow(() -> new PayverseException(WalletErrorCode.WALLET_NOT_FOUND, null));
        walletLock.lockForTransaction(walletId);
        return wallets.findById(walletId).orElseThrow();
    }
}
