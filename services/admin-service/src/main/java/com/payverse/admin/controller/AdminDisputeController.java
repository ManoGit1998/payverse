package com.payverse.admin.controller;

import com.payverse.admin.dto.DisputeResponse;
import com.payverse.admin.dto.PageResponse;
import com.payverse.admin.dto.ResolveDisputeRequest;
import com.payverse.admin.entity.DisputeStatus;
import com.payverse.admin.service.DisputeService;
import com.payverse.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADMIN and SUPPORT work the dispute queue. Resolving with REFUND opens a refund request that another ADMIN approves. */
@RestController
@RequestMapping("/api/v1/admin/disputes")
@Tag(name = "Admin: disputes")
@Validated
public class AdminDisputeController {

    private final DisputeService disputes;

    public AdminDisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @Operation(summary = "Disputes (oldest first for a status; all newest first without)")
    @GetMapping
    public PageResponse<DisputeResponse> list(@RequestParam(required = false) DisputeStatus status, @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = disputes.list(status, page, size).map(DisputeResponse::of);
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "One dispute")
    @GetMapping("/{id}")
    public DisputeResponse get(@PathVariable UUID id) {
        return DisputeResponse.of(disputes.get(id));
    }

    @Operation(summary = "Take the dispute (IN_REVIEW, assigned to me)")
    @PostMapping("/{id}/assign")
    public DisputeResponse assign(@PathVariable UUID id, AuthenticatedUser agent) {
        return DisputeResponse.of(disputes.assign(agent.subject(), id));
    }

    @Operation(summary = "Resolve: REJECTED, or REFUND (creates a refund request for ADMIN approval)")
    @PostMapping("/{id}/resolve")
    public DisputeResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveDisputeRequest request, AuthenticatedUser agent) {
        return DisputeResponse.of(disputes.resolve(agent.subject(), id, request.outcome(), request.note()));
    }
}
