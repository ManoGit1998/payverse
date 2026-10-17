package com.payverse.bill.controller;

import com.payverse.bill.dto.BillPaymentResponse;
import com.payverse.bill.dto.FetchBillRequest;
import com.payverse.bill.dto.FetchedBillResponse;
import com.payverse.bill.dto.PageResponse;
import com.payverse.bill.dto.PayBillRequest;
import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.entity.PaymentSource;
import com.payverse.bill.mapper.BillMapper;
import com.payverse.bill.service.BillPaymentService;
import com.payverse.bill.service.BillerService;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.common.web.idempotency.IdempotencyService;
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
@RequestMapping("/api/v1/bills")
@Tag(name = "Bills")
@Validated
public class BillController {

    private final BillerService billers;
    private final BillPaymentService payments;
    private final BillMapper mapper;

    public BillController(BillerService billers, BillPaymentService payments, BillMapper mapper) {
        this.billers = billers;
        this.payments = payments;
        this.mapper = mapper;
    }

    @Operation(summary = "Fetch the current bill (postpaid, electricity, credit card)")
    @PostMapping("/fetch")
    public FetchedBillResponse fetch(@Valid @RequestBody FetchBillRequest request) {
        return mapper.toResponse(billers.fetch(request.billerCode(), request.accountRef()));
    }

    @Operation(summary = "Pay a bill or recharge — Idempotency-Key and X-Txn-Pin required. 201 SUCCESS/FAILED, 202 PENDING, 200 replay")
    @PostMapping("/pay")
    public ResponseEntity<BillPaymentResponse> pay(@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                                   @RequestHeader(value = "X-Txn-Pin", required = false) String pin,
                                                   @Valid @RequestBody PayBillRequest request, AuthenticatedUser user) {
        BillPaymentService.PayResult result = payments.pay(new BillPaymentService.PayCommand(user.userId(), request.billerCode(),
                request.accountRef(), Money.inr(request.amount()), pin, idempotencyKey, PaymentSource.DIRECT, null));
        HttpStatus status = result.replayed() ? HttpStatus.OK : result.payment().isSettled() ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).header(IdempotencyService.REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(mapper.toResponse(result.payment()));
    }

    @Operation(summary = "Bill payment status")
    @GetMapping("/{id}")
    public BillPaymentResponse get(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(payments.get(user.userId(), id));
    }

    @Operation(summary = "My bill payments, newest first")
    @GetMapping("/history")
    public PageResponse<BillPaymentResponse> history(AuthenticatedUser user, @RequestParam(required = false) BillCategory category,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = payments.history(user.userId(), category, page, size);
        return new PageResponse<>(result.map(mapper::toResponse).getContent(), page, size, result.getTotalElements());
    }
}
