package com.payverse.payment.controller;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.common.web.idempotency.IdempotencyService;
import com.payverse.payment.dto.CollectResponse;
import com.payverse.payment.dto.CreateCollectRequest;
import com.payverse.payment.dto.PageResponse;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.mapper.PaymentMapper;
import com.payverse.payment.service.CollectRequestService;
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
@RequestMapping("/api/v1/collect-requests")
@Tag(name = "UPI collect requests")
@Validated
public class CollectRequestController {

    private final CollectRequestService collects;
    private final PaymentMapper mapper;

    public CollectRequestController(CollectRequestService collects, PaymentMapper mapper) {
        this.collects = collects;
        this.mapper = mapper;
    }

    @Operation(summary = "Request money from a UPI ID (the payer approves with their PIN)")
    @PostMapping
    public ResponseEntity<CollectResponse> create(@Valid @RequestBody CreateCollectRequest request, AuthenticatedUser user) {
        var created = collects.create(user.userId(), request.payerVpa(), Money.inr(request.amount()), request.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(created));
    }

    @Operation(summary = "Collect requests: INCOMING (I pay) or OUTGOING (I requested)")
    @GetMapping
    public PageResponse<CollectResponse> list(@RequestParam(defaultValue = "INCOMING") CollectRequestService.Direction direction,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                              AuthenticatedUser user) {
        var result = collects.list(user.userId(), direction, page, size);
        return new PageResponse<>(result.map(mapper::toResponse).getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "Approve and pay (requires Idempotency-Key and X-Txn-Pin)")
    @PostMapping("/{id}/approve")
    public CollectResponse approve(@PathVariable UUID id, @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                   @RequestHeader(value = "X-Txn-Pin", required = false) String pin, AuthenticatedUser user) {
        if (pin == null || pin.isBlank()) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_PIN_REQUIRED, null);
        }
        // the transfer itself is idempotent on the collect id, so approving twice can never pay twice
        return mapper.toResponse(collects.approve(user.userId(), id, pin));
    }

    @Operation(summary = "Reject")
    @PostMapping("/{id}/reject")
    public CollectResponse reject(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(collects.reject(user.userId(), id));
    }
}
