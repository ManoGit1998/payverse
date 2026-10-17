package com.payverse.transaction.controller;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.transaction.dto.PageResponse;
import com.payverse.transaction.dto.TransactionDetailResponse;
import com.payverse.transaction.dto.TransactionResponse;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.mapper.TransactionMapper;
import com.payverse.transaction.service.StatementService;
import com.payverse.transaction.service.TransactionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "History with filters, details and statements")
@Validated
public class TransactionController {

    private static final Duration MAX_STATEMENT_PERIOD = Duration.ofDays(366);

    private final TransactionQueryService queries;
    private final StatementService statements;
    private final TransactionMapper mapper;
    private final Clock clock;

    public TransactionController(TransactionQueryService queries, StatementService statements, TransactionMapper mapper, Clock clock) {
        this.queries = queries;
        this.statements = statements;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Operation(summary = "My transactions (filters: direction, status, period, amount range; sort: createdAt|amount)")
    @GetMapping
    public PageResponse<TransactionResponse> history(AuthenticatedUser user,
                                                     @RequestParam(required = false) TransactionResponse.Direction direction,
                                                     @RequestParam(required = false) TransactionStatus status,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                     @RequestParam(required = false) BigDecimal minAmount,
                                                     @RequestParam(required = false) BigDecimal maxAmount,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                     @RequestParam(defaultValue = "createdAt") String sort,
                                                     @RequestParam(defaultValue = "DESC") Sort.Direction order) {
        Page<Transaction> result = queries.history(user.userId(), new TransactionQueryService.HistoryFilter(direction, status, from, to,
                minAmount == null ? null : Money.inr(minAmount), maxAmount == null ? null : Money.inr(maxAmount)), page, size, sort, order);
        return new PageResponse<>(result.map(t -> mapper.toResponse(t, user.userId())).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "One transaction with its status history")
    @GetMapping("/{id}")
    public TransactionDetailResponse detail(@PathVariable UUID id, AuthenticatedUser user) {
        Transaction t = queries.get(user.userId(), id);
        boolean payer = t.getPayerUserId().equals(user.userId());
        return new TransactionDetailResponse(mapper.toResponse(t, user.userId()),
                payer ? queries.statusHistory(id).stream().map(mapper::toResponse).toList() : List.of());
    }

    @Operation(summary = "Statement of settled transactions as CSV or PDF (max 1 year)")
    @GetMapping("/statement")
    public ResponseEntity<byte[]> statement(AuthenticatedUser user,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                            @RequestParam(defaultValue = "csv") String format) {
        Instant end = to == null ? clock.instant() : to;
        if (!end.isAfter(from) || Duration.between(from, end).compareTo(MAX_STATEMENT_PERIOD) > 0) {
            throw new PayverseException(CommonErrorCode.VALIDATION_FAILED, "Statement period must be positive and at most 1 year");
        }
        List<Transaction> rows = queries.statementRows(user.userId(), from, end);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"csv".equalsIgnoreCase(format)) {
            throw new PayverseException(CommonErrorCode.VALIDATION_FAILED, "format must be csv or pdf");
        }
        byte[] body = pdf ? statements.pdf(user.userId(), rows, from, end) : statements.csv(user.userId(), rows);
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("payverse-statement." + (pdf ? "pdf" : "csv")).build().toString())
                .body(body);
    }
}
