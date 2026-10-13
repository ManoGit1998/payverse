package com.payverse.wallet.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.wallet.dto.AddMoneyResponse;
import com.payverse.wallet.dto.MoneyDto;
import com.payverse.wallet.dto.PostingResult;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.entity.SystemAccount;
import com.payverse.wallet.exception.WalletErrorCode;
import com.payverse.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Add money: check limits → charge the (mock) gateway → credit the wallet from PG_CLEARING.
 * Runs inside the Idempotency-Key transaction, so the posting and its replayable response commit together.
 */
@Service
public class AddMoneyService {

    private final WalletService walletService;
    private final WalletPostingService postings;
    private final PaymentGateway paymentGateway;
    private final WalletEventPublisher events;
    private final WalletRepository wallets;

    public AddMoneyService(WalletService walletService, WalletPostingService postings, PaymentGateway paymentGateway,
                           WalletEventPublisher events, WalletRepository wallets) {
        this.walletService = walletService;
        this.postings = postings;
        this.paymentGateway = paymentGateway;
        this.events = events;
        this.wallets = wallets;
    }

    @Transactional
    public AddMoneyResponse addMoney(UUID userId, Money amount, String method, String idempotencyKey) {
        walletService.getOrCreate(userId);
        postings.preflight(userId, amount, LimitPolicy.LOAD); // never charge a card for money we can't accept
        PaymentGateway.ChargeResult charge = paymentGateway.charge(userId, amount, method, idempotencyKey);
        if (charge instanceof PaymentGateway.ChargeResult.Declined(String reason)) {
            throw new PayverseException(WalletErrorCode.WALLET_PAYMENT_DECLINED, reason);
        }
        String reference = ((PaymentGateway.ChargeResult.Succeeded) charge).reference();
        PostingResult result = postings.post(new WalletPostingService.PostingCommand(userId, WalletPostingService.Direction.CREDIT,
                amount, SystemAccount.PG_CLEARING, JournalType.ADD_MONEY, LimitPolicy.LOAD,
                "add-money:" + userId + ":" + idempotencyKey, "PgCharge", reference, "Added via " + method, userId.toString()));
        if (!result.replayed()) {
            events.moneyAdded(wallets.findById(result.walletId()).orElseThrow(), amount, reference, result.journalId());
        }
        return new AddMoneyResponse(result.journalId(), MoneyDto.of(amount), MoneyDto.of(result.balanceAfter()), reference,
                "SUCCESS", result.postedAt());
    }
}
