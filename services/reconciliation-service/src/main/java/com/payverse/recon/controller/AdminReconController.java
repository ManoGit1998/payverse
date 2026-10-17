package com.payverse.recon.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.recon.dto.MismatchResponse;
import com.payverse.recon.dto.PageResponse;
import com.payverse.recon.dto.ReconRunResponse;
import com.payverse.recon.dto.ResolveMismatchRequest;
import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.entity.ReconRun;
import com.payverse.recon.service.ReconService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** ADMIN and SUPPORT read; only ADMIN starts runs and resolves mismatches. */
@RestController
@RequestMapping("/api/v1/admin/recon")
@Tag(name = "Admin: reconciliation")
@Validated
public class AdminReconController {

    private final ReconService recon;
    private final Clock clock;

    public AdminReconController(ReconService recon, Clock clock) {
        this.recon = recon;
        this.clock = clock;
    }

    @Operation(summary = "Run a job now for a business day (default: today, IST) — returns the RUNNING run; poll it")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/jobs/{job}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReconRunResponse run(@PathVariable ReconJob job,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                AuthenticatedUser user) {
        LocalDate day = date == null ? LocalDate.now(clock.withZone(ReconRun.IST)) : date;
        return ReconRunResponse.of(recon.trigger(job, day, user.subject()));
    }

    @Operation(summary = "Runs, newest first")
    @GetMapping("/runs")
    public PageResponse<ReconRunResponse> runs(@RequestParam(required = false) ReconJob job, @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = recon.runs(job, page, size).map(ReconRunResponse::of);
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "One run")
    @GetMapping("/runs/{id}")
    public ReconRunResponse run(@PathVariable UUID id) {
        return ReconRunResponse.of(recon.run(id));
    }

    @Operation(summary = "Mismatches found by a run")
    @GetMapping("/runs/{id}/mismatches")
    public PageResponse<MismatchResponse> mismatches(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        var result = recon.mismatches(id, page, size).map(MismatchResponse::of);
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "Open mismatches across runs")
    @GetMapping("/mismatches")
    public PageResponse<MismatchResponse> open(@RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        var result = recon.open(page, size).map(MismatchResponse::of);
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "Resolve a mismatch with an explanation (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/mismatches/{id}/resolve")
    public MismatchResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveMismatchRequest request, AuthenticatedUser user) {
        return MismatchResponse.of(recon.resolve(user.subject(), id, request.note()));
    }
}
