package com.payverse.wallet.controller;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.common.web.idempotency.IdempotencyService;
import com.payverse.wallet.dto.AddMoneyRequest;
import com.payverse.wallet.dto.AddMoneyResponse;
import com.payverse.wallet.dto.LedgerEntryResponse;
import com.payverse.wallet.dto.PageResponse;
import com.payverse.wallet.dto.WalletResponse;
import com.payverse.wallet.dto.WithdrawRequest;
import com.payverse.wallet.dto.WithdrawResponse;
import com.payverse.wallet.exception.WalletErrorCode;
import com.payverse.wallet.service.AddMoneyService;
import com.payverse.wallet.service.LedgerQueryService;
import com.payverse.wallet.service.WalletService;
import com.payverse.wallet.service.WithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/wallets/me")
@Tag(name = "Wallet")
@Validated
public class WalletController {

    private final WalletService walletService;
    private final AddMoneyService addMoneyService;
    private final WithdrawalService withdrawalService;
    private final LedgerQueryService ledger;
    private final IdempotencyService idempotency;

    public WalletController(WalletService walletService, AddMoneyService addMoneyService, WithdrawalService withdrawalService,
                            LedgerQueryService ledger, IdempotencyService idempotency) {
        this.walletService = walletService;
        this.addMoneyService = addMoneyService;
        this.withdrawalService = withdrawalService;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @Operation(summary = "My wallet: balance, status, KYC tier and limits")
    @GetMapping
    public WalletResponse me(AuthenticatedUser user) {
        return walletService.summary(user.userId());
    }

    @Operation(summary = "Ledger history of my wallet (newest first)")
    @GetMapping("/ledger")
    public PageResponse<LedgerEntryResponse> ledger(AuthenticatedUser user,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ledger.history(user.userId(), from, to, page, size);
    }

    @Operation(summary = "Add money via the (MOCK) payment gateway — requires Idempotency-Key")
    @PostMapping("/add-money")
    public ResponseEntity<AddMoneyResponse> addMoney(@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                                     @Valid @RequestBody AddMoneyRequest request, AuthenticatedUser user) {
        return idempotency.execute("add-money:" + user.userId(), idempotencyKey, request, AddMoneyResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(addMoneyService.addMoney(user.userId(),
                        Money.inr(request.amount()), request.paymentMethod().name(), idempotencyKey)));
    }

    @Operation(summary = "Withdraw to a linked bank account — requires Idempotency-Key and X-Txn-Pin")
    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawResponse> withdraw(@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                                     @RequestHeader(value = "X-Txn-Pin", required = false) String pin,
                                                     @Valid @RequestBody WithdrawRequest request, AuthenticatedUser user) {
        if (pin == null || pin.isBlank()) {
            throw new PayverseException(WalletErrorCode.WALLET_PIN_REQUIRED, null);
        }
        return idempotency.execute("withdraw:" + user.userId(), idempotencyKey, request, WithdrawResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(withdrawalService.withdraw(user.userId(),
                        Money.inr(request.amount()), request.bankAccountId(), pin, idempotencyKey)));
    }
}
