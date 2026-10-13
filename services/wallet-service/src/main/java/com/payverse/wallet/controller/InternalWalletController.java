package com.payverse.wallet.controller;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.common.web.idempotency.IdempotencyService;
import com.payverse.wallet.dto.FreezeRequest;
import com.payverse.wallet.dto.IntegrityReport;
import com.payverse.wallet.dto.InternalPostingRequest;
import com.payverse.wallet.dto.PostingResponse;
import com.payverse.wallet.dto.PostingResult;
import com.payverse.wallet.dto.ReverseRequest;
import com.payverse.wallet.dto.WalletResponse;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.entity.SystemAccount;
import com.payverse.wallet.service.LedgerQueryService;
import com.payverse.wallet.service.LimitPolicy;
import com.payverse.wallet.service.WalletPostingService;
import com.payverse.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Saga steps and admin operations for other services (role SERVICE). Every posting requires an
 * Idempotency-Key, scoped by the calling service: retries return the original result (HTTP 200,
 * {@code replayed=true}) instead of moving money twice.
 */
@RestController
@RequestMapping("/internal/wallets")
@Tag(name = "Internal")
public class InternalWalletController {

    private final WalletPostingService postings;
    private final WalletService walletService;
    private final LedgerQueryService ledger;

    public InternalWalletController(WalletPostingService postings, WalletService walletService, LedgerQueryService ledger) {
        this.postings = postings;
        this.walletService = walletService;
        this.ledger = ledger;
    }

    @Operation(summary = "Wallet summary")
    @GetMapping("/{userId}")
    public WalletResponse wallet(@PathVariable UUID userId) {
        walletService.get(userId);
        return walletService.summary(userId);
    }

    @Operation(summary = "Debit a wallet (TRANSFER → TRANSFER_SUSPENSE, BILL_PAYMENT → BILLER_SETTLEMENT)")
    @PostMapping("/{userId}/debit")
    public ResponseEntity<PostingResponse> debit(@PathVariable UUID userId, @RequestHeader(IdempotencyService.HEADER) String key,
                                                 @Valid @RequestBody InternalPostingRequest request, AuthenticatedUser caller) {
        var route = switch (request.purpose()) {
            case TRANSFER -> new Route(SystemAccount.TRANSFER_SUSPENSE, JournalType.TRANSFER_DEBIT);
            case BILL_PAYMENT -> new Route(SystemAccount.BILLER_SETTLEMENT, JournalType.BILL_PAYMENT);
            case BILL_REFUND, CASHBACK -> throw new PayverseException(CommonErrorCode.VALIDATION_FAILED, request.purpose() + " is a credit purpose");
        };
        return respond(postings.post(command(userId, WalletPostingService.Direction.DEBIT, request, route, LimitPolicy.DEBIT, key, caller)));
    }

    @Operation(summary = "Credit a wallet (TRANSFER ← TRANSFER_SUSPENSE, CASHBACK ← REWARDS_POOL, BILL_REFUND ← BILLER_SETTLEMENT)")
    @PostMapping("/{userId}/credit")
    public ResponseEntity<PostingResponse> credit(@PathVariable UUID userId, @RequestHeader(IdempotencyService.HEADER) String key,
                                                  @Valid @RequestBody InternalPostingRequest request, AuthenticatedUser caller) {
        var route = switch (request.purpose()) {
            case TRANSFER -> new Route(SystemAccount.TRANSFER_SUSPENSE, JournalType.TRANSFER_CREDIT);
            case CASHBACK -> new Route(SystemAccount.REWARDS_POOL, JournalType.CASHBACK);
            case BILL_REFUND -> new Route(SystemAccount.BILLER_SETTLEMENT, JournalType.BILL_REFUND);
            case BILL_PAYMENT -> throw new PayverseException(CommonErrorCode.VALIDATION_FAILED, "BILL_PAYMENT is a debit purpose");
        };
        return respond(postings.post(command(userId, WalletPostingService.Direction.CREDIT, request, route, LimitPolicy.CREDIT, key, caller)));
    }

    @Operation(summary = "Reverse (compensate) a journal on this wallet — exempt from limits and freezes, once per journal")
    @PostMapping("/{userId}/reverse")
    public ResponseEntity<PostingResponse> reverse(@PathVariable UUID userId, @RequestHeader(IdempotencyService.HEADER) String key,
                                                   @Valid @RequestBody ReverseRequest request, AuthenticatedUser caller) {
        return respond(postings.reverse(userId, request.journalId(), scoped(caller, key), request.reason(), caller.subject()));
    }

    @Operation(summary = "Freeze a wallet (admin-service)")
    @PostMapping("/{userId}/freeze")
    public WalletResponse freeze(@PathVariable UUID userId, @Valid @RequestBody FreezeRequest request, AuthenticatedUser caller) {
        walletService.freeze(userId, request.reason(), caller.subject());
        return walletService.summary(userId);
    }

    @Operation(summary = "Unfreeze a wallet (admin-service)")
    @PostMapping("/{userId}/unfreeze")
    public WalletResponse unfreeze(@PathVariable UUID userId, AuthenticatedUser caller) {
        walletService.unfreeze(userId, caller.subject());
        return walletService.summary(userId);
    }

    @Operation(summary = "Ledger integrity report (balances = sum of entries, journals balanced)")
    @GetMapping("/integrity")
    public IntegrityReport integrity() {
        return ledger.checkIntegrity();
    }

    private record Route(SystemAccount contra, JournalType type) {
    }

    private static WalletPostingService.PostingCommand command(UUID userId, WalletPostingService.Direction direction,
                                                               InternalPostingRequest request, Route route, LimitPolicy policy,
                                                               String key, AuthenticatedUser caller) {
        return new WalletPostingService.PostingCommand(userId, direction, Money.inr(request.amount()), route.contra(), route.type(),
                policy, scoped(caller, key), request.referenceType(), request.referenceId(), request.description(), caller.subject());
    }

    private static String scoped(AuthenticatedUser caller, String key) {
        return "svc:" + caller.subject() + ":" + key;
    }

    private static ResponseEntity<PostingResponse> respond(PostingResult result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(PostingResponse.of(result));
    }
}
