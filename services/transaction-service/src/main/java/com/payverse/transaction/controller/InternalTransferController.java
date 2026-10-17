package com.payverse.transaction.controller;

import com.payverse.common.money.Money;
import com.payverse.common.web.idempotency.IdempotencyService;
import com.payverse.transaction.dto.InternalTransferRequest;
import com.payverse.transaction.dto.TransferOutcome;
import com.payverse.transaction.entity.PayeeType;
import com.payverse.transaction.mapper.TransactionMapper;
import com.payverse.transaction.service.PayeeResolver;
import com.payverse.transaction.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service transfers (role SERVICE): payment-service starts one when a payer approves a collect request. */
@RestController
@RequestMapping("/internal/transfers")
@Tag(name = "Internal")
public class InternalTransferController {

    private final TransferService transfers;
    private final TransactionMapper mapper;

    public InternalTransferController(TransferService transfers, TransactionMapper mapper) {
        this.transfers = transfers;
        this.mapper = mapper;
    }

    @Operation(summary = "Transfer between two known users (PIN still verified)")
    @PostMapping
    public ResponseEntity<TransferOutcome> transfer(@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                                    @RequestHeader(value = "X-Txn-Pin", required = false) String pin,
                                                    @Valid @RequestBody InternalTransferRequest request) {
        TransferService.TransferResult result = transfers.transfer(new TransferService.TransferCommand(request.payerUserId(),
                new PayeeResolver.Target(PayeeType.USER, request.payeeUserId().toString(), request.payeeRef()),
                Money.inr(request.amount()), request.note(), request.source(), request.sourceId(), pin, idempotencyKey));
        return TransferController.respond(result, mapper.toOutcome(result.transaction()));
    }
}
