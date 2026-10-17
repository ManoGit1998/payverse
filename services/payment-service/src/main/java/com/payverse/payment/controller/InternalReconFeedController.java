package com.payverse.payment.controller;

import com.payverse.payment.dto.PageResponse;
import com.payverse.payment.dto.PgChargeRecord;
import com.payverse.payment.dto.UpiSwitchRecordResponse;
import com.payverse.payment.entity.PgOrderStatus;
import com.payverse.payment.service.ReconFeedService;
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

/** Feeds for reconciliation-service (role SERVICE). */
@RestController
@RequestMapping("/internal")
@Tag(name = "Internal: reconciliation feeds")
@Validated
public class InternalReconFeedController {

    private final ReconFeedService feeds;

    public InternalReconFeedController(ReconFeedService feeds) {
        this.feeds = feeds;
    }

    @Operation(summary = "PG charges created in [from, to)")
    @GetMapping("/pg/charges")
    public PageResponse<PgChargeRecord> charges(@RequestParam(defaultValue = "SUCCEEDED") PgOrderStatus status,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int size) {
        var result = feeds.charges(status, from, to, page, size).map(o -> new PgChargeRecord(o.getId(), o.getUserId(), o.getAmount(),
                o.getProviderRef(), o.getStatus().name(), o.getCreatedAt()));
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "UPI switch records (RRNs) created in [from, to)")
    @GetMapping("/upi/switch-records")
    public PageResponse<UpiSwitchRecordResponse> switchRecords(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                                               @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int size) {
        var result = feeds.switchRecords(from, to, page, size).map(r -> new UpiSwitchRecordResponse(r.getRrn(), r.getTransactionId(),
                r.getAmount(), r.getStatus(), r.getCreatedAt()));
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }
}
