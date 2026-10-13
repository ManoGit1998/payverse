package com.payverse.wallet.service;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.common.money.Money;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.wallet.entity.Journal;
import com.payverse.wallet.entity.LedgerEntry;
import com.payverse.wallet.entity.SystemAccount;
import com.payverse.wallet.entity.Wallet;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** wallet-service events via the transactional outbox (call inside the business transaction). */
@Service
public class WalletEventPublisher {

    private static final Map<UUID, String> SYSTEM_CODES = Arrays.stream(SystemAccount.values())
            .collect(Collectors.toUnmodifiableMap(SystemAccount::id, Enum::name));

    private final OutboxPublisher outbox;

    public WalletEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void walletCreated(Wallet wallet) {
        outbox.publish(EventType.WALLET_CREATED, wallet.getId().toString(),
                new WalletEvents.WalletCreated(wallet.getId(), wallet.getUserId(), wallet.getKycTier().name()));
    }

    public void statusChanged(Wallet wallet, String actor) {
        outbox.publish(EventType.WALLET_STATUS_CHANGED, wallet.getId().toString(), new WalletEvents.WalletStatusChanged(
                wallet.getId(), wallet.getUserId(), wallet.getStatus().name(), wallet.getStatusReason(), actor));
    }

    public void moneyAdded(Wallet wallet, Money amount, String pgReference, UUID journalId) {
        outbox.publish(EventType.MONEY_ADDED, wallet.getId().toString(),
                new WalletEvents.MoneyAdded(wallet.getId(), wallet.getUserId(), Amount.of(amount), pgReference, journalId));
    }

    public void moneyWithdrawn(Wallet wallet, Money amount, UUID bankAccountId, String payoutReference, UUID journalId) {
        outbox.publish(EventType.MONEY_WITHDRAWN, wallet.getId().toString(), new WalletEvents.MoneyWithdrawn(
                wallet.getId(), wallet.getUserId(), Amount.of(amount), bankAccountId, payoutReference, journalId));
    }

    /** Every journal is published for reconciliation-service. */
    public void ledgerPosted(Journal journal, List<LedgerEntry> entries, Wallet wallet) {
        Function<UUID, String> code = accountId -> accountId.equals(wallet.getLedgerAccountId())
                ? "WALLET:" + wallet.getId() : SYSTEM_CODES.getOrDefault(accountId, accountId.toString());
        List<WalletEvents.LedgerPosted.Entry> payloadEntries = entries.stream()
                .map(e -> new WalletEvents.LedgerPosted.Entry(code.apply(e.getAccountId()), e.getSide().name(), Amount.of(e.getAmountMoney())))
                .toList();
        outbox.publish(EventType.LEDGER_POSTED, journal.getId().toString(), new WalletEvents.LedgerPosted(
                journal.getId(), journal.getType().name(), journal.getReferenceType(), journal.getReferenceId(), payloadEntries));
    }
}
