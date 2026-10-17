package com.payverse.transaction.controller;

import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.common.web.idempotency.IdempotencyService;
import com.payverse.transaction.dto.CreateMoneyRequest;
import com.payverse.transaction.dto.MoneyRequestResponse;
import com.payverse.transaction.dto.PageResponse;
import com.payverse.transaction.dto.TransferResponse;
import com.payverse.transaction.mapper.TransactionMapper;
import com.payverse.transaction.service.MoneyRequestService;
import com.payverse.transaction.service.PayeeResolver;
import com.payverse.transaction.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/money-requests")
@Tag(name = "Money requests")
@Validated
public class MoneyRequestController {

    private final MoneyRequestService requests;
    private final TransactionMapper mapper;

    public MoneyRequestController(MoneyRequestService requests, TransactionMapper mapper) {
        this.requests = requests;
        this.mapper = mapper;
    }

    @Operation(summary = "Ask someone (MOBILE or VPA) for money")
    @PostMapping
    public ResponseEntity<MoneyRequestResponse> create(@Valid @RequestBody CreateMoneyRequest request, AuthenticatedUser user) {
        var created = requests.create(user.userId(), new PayeeResolver.Target(request.payer().type(), request.payer().value(), null),
                Money.inr(request.amount()), request.note(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(created, user.userId()));
    }

    @Operation(summary = "INCOMING (asked of me) or OUTGOING (I asked)")
    @GetMapping
    public PageResponse<MoneyRequestResponse> list(@RequestParam(defaultValue = "INCOMING") MoneyRequestService.Direction direction,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                   AuthenticatedUser user) {
        var result = requests.list(user.userId(), direction, page, size);
        return new PageResponse<>(result.map(r -> mapper.toResponse(r, user.userId())).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "Pay a request — requires Idempotency-Key and X-Txn-Pin")
    @PostMapping("/{id}/pay")
    public ResponseEntity<TransferResponse> pay(@PathVariable UUID id, @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                                @RequestHeader(value = "X-Txn-Pin", required = false) String pin, AuthenticatedUser user) {
        TransferService.TransferResult result = requests.pay(user.userId(), id, pin, idempotencyKey);
        return TransferController.respond(result, mapper.toTransfer(result.transaction()));
    }

    @Operation(summary = "Decline a request made to me")
    @PostMapping("/{id}/decline")
    public MoneyRequestResponse decline(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(requests.decline(user.userId(), id), user.userId());
    }

    @Operation(summary = "Cancel a request I made")
    @PostMapping("/{id}/cancel")
    public MoneyRequestResponse cancel(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(requests.cancel(user.userId(), id), user.userId());
    }
}
