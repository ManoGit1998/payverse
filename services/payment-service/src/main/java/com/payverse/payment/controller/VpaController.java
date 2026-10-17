package com.payverse.payment.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.payment.dto.CreateVpaRequest;
import com.payverse.payment.dto.ResolvedVpaResponse;
import com.payverse.payment.dto.VpaResponse;
import com.payverse.payment.mapper.PaymentMapper;
import com.payverse.payment.service.VpaService;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/vpa")
@Tag(name = "UPI IDs")
public class VpaController {

    private final VpaService vpaService;
    private final PaymentMapper mapper;

    public VpaController(VpaService vpaService, PaymentMapper mapper) {
        this.vpaService = vpaService;
        this.mapper = mapper;
    }

    @Operation(summary = "Create a custom UPI ID (name@payverse, max 3 per user)")
    @PostMapping
    public ResponseEntity<VpaResponse> create(@Valid @RequestBody CreateVpaRequest request, AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(vpaService.create(user.userId(), request.name())));
    }

    @Operation(summary = "My UPI IDs (the default one is created automatically)")
    @GetMapping("/me")
    public List<VpaResponse> mine(AuthenticatedUser user) {
        return vpaService.list(user.userId()).stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "Verify a UPI ID before paying (shows the payee name)")
    @GetMapping("/{vpa}/resolve")
    public ResolvedVpaResponse resolve(@PathVariable String vpa) {
        VpaService.ResolvedVpa resolved = vpaService.resolve(vpa);
        return new ResolvedVpaResponse(resolved.vpa(), resolved.payeeName());
    }
}
