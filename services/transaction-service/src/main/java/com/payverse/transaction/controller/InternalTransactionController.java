package com.payverse.transaction.controller;

import com.payverse.common.error.PayverseException;
import com.payverse.transaction.dto.InternalTransactionResponse;
import com.payverse.transaction.dto.PageResponse;
import com.payverse.transaction.dto.RefundRequest;
import com.payverse.transaction.entity.RefundStatus;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.exception.TransactionErrorCode;
import com.payverse.transaction.service.RefundService;
import com.payverse.transaction.service.TransactionQueryService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Service-to-service API (role SERVICE): lookups for admin/recon and refunds for admin-service. */
@RestController
@RequestMapping("/internal/transactions")
@Tag(name = "Internal: transactions")
@Validated
public class InternalTransactionController {

    private final TransactionQueryService queries;
    private final RefundService refunds;

    public InternalTransactionController(TransactionQueryService queries, RefundService refunds) {
        this.queries = queries;
        this.refunds = refunds;
    }

    @Operation(summary = "One transaction")
    @GetMapping("/{id}")
    public InternalTransactionResponse get(@PathVariable UUID id) {
        return view(queries.find(id).orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_NOT_FOUND, null)));
    }

    @Operation(summary = "Transactions completed in [from, to), by status (default SUCCESS and REVERSED) — for reconciliation")
    @GetMapping
    public PageResponse<InternalTransactionResponse> completed(@RequestParam(defaultValue = "SUCCESS,REVERSED") List<TransactionStatus> status,
                                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                                               @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int size) {
        var result = queries.completedBetween(status, from, to, page, size).map(InternalTransactionController::view);
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "Refund a SUCCESS transfer (idempotent per refundId; 200 COMPLETED/FAILED, 202 PENDING — call again)")
    @PostMapping("/{id}/refund")
    public ResponseEntity<InternalTransactionResponse> refund(@PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
        Transaction t = refunds.refund(id, request.refundId(), request.reason());
        boolean pending = t.getRefundStatus() == RefundStatus.PENDING && request.refundId().equals(t.getRefundId());
        return ResponseEntity.status(pending ? HttpStatus.ACCEPTED : HttpStatus.OK).body(view(t));
    }

    static InternalTransactionResponse view(Transaction t) {
        return new InternalTransactionResponse(t.getId(), t.getType().name(), t.getStatus(), t.getPayerUserId(), t.getPayeeUserId(), t.getPayeeRef(),
                t.getAmount(), t.getCurrency(), t.getFailureCode(), t.getCreatedAt(), t.getCompletedAt(), t.getRefundId(), t.getRefundStatus(),
                t.getRefundFailureCode());
    }
}
