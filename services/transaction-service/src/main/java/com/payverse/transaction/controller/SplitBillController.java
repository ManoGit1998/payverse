package com.payverse.transaction.controller;

import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.transaction.dto.CreateSplitBillRequest;
import com.payverse.transaction.dto.SplitBillResponse;
import com.payverse.transaction.mapper.TransactionMapper;
import com.payverse.transaction.service.SplitBillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/split-bills")
@Tag(name = "Split bills")
public class SplitBillController {

    private final SplitBillService splits;
    private final TransactionMapper mapper;

    public SplitBillController(SplitBillService splits, TransactionMapper mapper) {
        this.splits = splits;
        this.mapper = mapper;
    }

    @Operation(summary = "Split a bill equally or by custom amounts (creates a money request per participant)")
    @PostMapping
    public ResponseEntity<SplitBillResponse> create(@Valid @RequestBody CreateSplitBillRequest request, AuthenticatedUser user) {
        var participants = request.participants().stream()
                .map(p -> new SplitBillService.Share(p.type(), p.value(), p.amount() == null ? null : Money.inr(p.amount())))
                .toList();
        var view = splits.create(user.userId(), Money.inr(request.total()), request.note(),
                request.includeMe() == null || request.includeMe(), participants);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(view));
    }

    @Operation(summary = "A split bill with each share's status")
    @GetMapping("/{id}")
    public SplitBillResponse get(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(splits.get(user.userId(), id));
    }
}
