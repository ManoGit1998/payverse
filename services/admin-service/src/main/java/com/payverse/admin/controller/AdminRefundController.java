package com.payverse.admin.controller;

import com.payverse.admin.dto.CreateRefundRequest;
import com.payverse.admin.dto.NoteRequest;
import com.payverse.admin.dto.PageResponse;
import com.payverse.admin.dto.RefundResponse;
import com.payverse.admin.entity.RefundStatus;
import com.payverse.admin.service.RefundService;
import com.payverse.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Maker-checker: SUPPORT or ADMIN requests, a different ADMIN approves or rejects. */
@RestController
@RequestMapping("/api/v1/admin/refunds")
@Tag(name = "Admin: refunds")
@Validated
public class AdminRefundController {

    private final RefundService refunds;

    public AdminRefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @Operation(summary = "Request a full refund of a SUCCESS transfer (maker)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RefundResponse request(@Valid @RequestBody CreateRefundRequest request, AuthenticatedUser maker) {
        return RefundResponse.of(refunds.request(maker.subject(), request.transactionId(), request.reason(), null));
    }

    @Operation(summary = "Refund requests (oldest first for a status)")
    @GetMapping
    public PageResponse<RefundResponse> list(@RequestParam(required = false) RefundStatus status, @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = refunds.list(status, page, size).map(RefundResponse::of);
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "One refund request")
    @GetMapping("/{id}")
    public RefundResponse get(@PathVariable UUID id) {
        return RefundResponse.of(refunds.get(id));
    }

    @Operation(summary = "Approve (checker, ADMIN, not the requester) — executes the refund")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/approve")
    public RefundResponse approve(@PathVariable UUID id, AuthenticatedUser checker) {
        return RefundResponse.of(refunds.approve(checker.subject(), id));
    }

    @Operation(summary = "Reject (checker, ADMIN, not the requester)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/reject")
    public RefundResponse reject(@PathVariable UUID id, @Valid @RequestBody NoteRequest request, AuthenticatedUser checker) {
        return RefundResponse.of(refunds.reject(checker.subject(), id, request.note()));
    }
}
