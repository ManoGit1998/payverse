package com.payverse.wallet.service;

import com.payverse.common.money.Money;
import com.payverse.wallet.client.AuthClient;
import com.payverse.wallet.client.UserClient;
import com.payverse.wallet.dto.MoneyDto;
import com.payverse.wallet.dto.PostingResult;
import com.payverse.wallet.dto.WithdrawResponse;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.entity.SystemAccount;
import com.payverse.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Withdraw to a linked bank account: verify the transaction PIN (auth-service) → check the bank
 * account belongs to the user (user-service) → debit the wallet into PAYOUT_CLEARING → initiate the
 * (mock) payout. A payout failure rolls the whole withdrawal back.
 */
@Service
public class WithdrawalService {

    private final AuthClient authClient;
    private final UserClient userClient;
    private final WalletPostingService postings;
    private final PayoutGateway payoutGateway;
    private final WalletEventPublisher events;
    private final WalletRepository wallets;

    public WithdrawalService(AuthClient authClient, UserClient userClient, WalletPostingService postings,
                             PayoutGateway payoutGateway, WalletEventPublisher events, WalletRepository wallets) {
        this.authClient = authClient;
        this.userClient = userClient;
        this.postings = postings;
        this.payoutGateway = payoutGateway;
        this.events = events;
        this.wallets = wallets;
    }

    @Transactional
    public WithdrawResponse withdraw(UUID userId, Money amount, UUID bankAccountId, String pin, String idempotencyKey) {
        authClient.verifyPin(userId, pin);
        UserClient.BankAccount account = userClient.bankAccount(userId, bankAccountId);
        String journalKey = "withdraw:" + userId + ":" + idempotencyKey;
        PostingResult result = postings.post(new WalletPostingService.PostingCommand(userId, WalletPostingService.Direction.DEBIT,
                amount, SystemAccount.PAYOUT_CLEARING, JournalType.WITHDRAWAL, LimitPolicy.DEBIT, journalKey,
                "BankAccount", bankAccountId.toString(), "Withdrawal to " + account.maskedAccountNumber(), userId.toString()));
        String payoutReference = payoutGateway.initiate(userId, bankAccountId, amount, journalKey);
        if (!result.replayed()) {
            events.moneyWithdrawn(wallets.findById(result.walletId()).orElseThrow(), amount, bankAccountId, payoutReference,
                    result.journalId());
        }
        return new WithdrawResponse(result.journalId(), MoneyDto.of(amount), MoneyDto.of(result.balanceAfter()), bankAccountId,
                account.maskedAccountNumber(), payoutReference, "PROCESSING", result.postedAt());
    }
}
