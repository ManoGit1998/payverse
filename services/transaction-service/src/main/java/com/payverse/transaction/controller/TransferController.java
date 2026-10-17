package com.payverse.transaction.controller;

import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.common.web.idempotency.IdempotencyService;
import com.payverse.transaction.dto.TransferRequest;
import com.payverse.transaction.dto.TransferResponse;
import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.entity.TransferSource;
import com.payverse.transaction.mapper.TransactionMapper;
import com.payverse.transaction.service.PayeeResolver;
import com.payverse.transaction.service.TransactionQueryService;
import com.payverse.transaction.service.TransferService;
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

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "P2P transfers by mobile, UPI ID or QR (orchestrated saga)")
public class TransferController {

    private final TransferService transfers;
    private final TransactionQueryService queries;
    private final TransactionMapper mapper;

    public TransferController(TransferService transfers, TransactionQueryService queries, TransactionMapper mapper) {
        this.transfers = transfers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Operation(summary = "Send money — requires Idempotency-Key and X-Txn-Pin. 201 SUCCESS/FAILED, 202 PENDING, 200 replay")
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                                     @RequestHeader(value = "X-Txn-Pin", required = false) String pin,
                                                     @Valid @RequestBody TransferRequest request, AuthenticatedUser user) {
        TransferService.TransferResult result = transfers.transfer(new TransferService.TransferCommand(user.userId(),
                new PayeeResolver.Target(request.payee().type(), request.payee().value(), null),
                request.amount() == null ? null : Money.inr(request.amount()), request.note(), TransferSource.DIRECT, null, pin,
                idempotencyKey));
        return respond(result, mapper.toTransfer(result.transaction()));
    }

    @Operation(summary = "Transfer status (poll while PENDING)")
    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toTransfer(queries.get(user.userId(), id));
    }

    static <T> ResponseEntity<T> respond(TransferService.TransferResult result, T body) {
        HttpStatus status = result.replayed() ? HttpStatus.OK
                : result.transaction().getStatus() == TransactionStatus.PENDING || result.transaction().getStatus() == TransactionStatus.INITIATED
                ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).header(IdempotencyService.REPLAYED_HEADER, String.valueOf(result.replayed())).body(body);
    }
}
