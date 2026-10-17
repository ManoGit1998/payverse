package com.payverse.payment.controller;

import com.payverse.common.money.Money;
import com.payverse.payment.dto.DecodeQrRequest;
import com.payverse.payment.dto.InternalDecodedQrResponse;
import com.payverse.payment.dto.InternalVpaResponse;
import com.payverse.payment.dto.PgChargeRequest;
import com.payverse.payment.dto.PgChargeResponse;
import com.payverse.payment.mapper.PaymentMapper;
import com.payverse.payment.service.PaymentGatewayService;
import com.payverse.payment.service.QrService;
import com.payverse.payment.service.VpaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Service-to-service API (role SERVICE): VPA/QR resolution for transaction-service, charges for wallet-service. */
@RestController
@RequestMapping("/internal")
@Tag(name = "Internal")
public class InternalPaymentController {

    private final VpaService vpaService;
    private final QrService qrService;
    private final PaymentGatewayService gateway;
    private final PaymentMapper mapper;

    public InternalPaymentController(VpaService vpaService, QrService qrService, PaymentGatewayService gateway, PaymentMapper mapper) {
        this.vpaService = vpaService;
        this.qrService = qrService;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @Operation(summary = "Resolve a VPA to a user")
    @GetMapping("/vpa/{vpa}")
    public InternalVpaResponse resolve(@PathVariable String vpa) {
        VpaService.ResolvedVpa resolved = vpaService.resolve(vpa);
        return new InternalVpaResponse(resolved.vpa(), resolved.userId(), resolved.payeeName());
    }

    @Operation(summary = "A user's default VPA")
    @GetMapping("/vpa/by-user/{userId}")
    public InternalVpaResponse defaultVpa(@PathVariable UUID userId) {
        var vpa = vpaService.ensureDefault(userId);
        return new InternalVpaResponse(vpa.getHandle(), userId, null);
    }

    @Operation(summary = "Decode a UPI QR payload (includes the payee user id)")
    @PostMapping("/qr/decode")
    public InternalDecodedQrResponse decode(@Valid @RequestBody DecodeQrRequest request) {
        return mapper.toInternal(qrService.decode(request.payload()));
    }

    @Operation(summary = "Charge through the (MOCK) payment gateway — idempotent per idempotencyKey")
    @PostMapping("/pg/charges")
    public PgChargeResponse charge(@Valid @RequestBody PgChargeRequest request) {
        return mapper.toResponse(gateway.charge(request.userId(), Money.inr(request.amount()), request.method(), request.idempotencyKey()));
    }
}
