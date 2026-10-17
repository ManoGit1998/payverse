package com.payverse.wallet.controller;

import com.payverse.wallet.dto.PageResponse;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.model.JournalSummary;
import com.payverse.wallet.service.LedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/internal/ledger")
@Tag(name = "Internal: ledger")
@Validated
public class InternalLedgerController {

    private final LedgerQueryService ledger;

    public InternalLedgerController(LedgerQueryService ledger) {
        this.ledger = ledger;
    }

    @Operation(summary = "Journals of one type created in [from, to) — for reconciliation")
    @GetMapping("/journals")
    public PageResponse<JournalSummary> journals(@RequestParam JournalType type,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int size) {
        var result = ledger.journals(type, from, to, page, size);
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }
}
