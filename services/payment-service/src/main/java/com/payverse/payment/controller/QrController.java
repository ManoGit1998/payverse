package com.payverse.payment.controller;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.payment.dto.DecodeQrRequest;
import com.payverse.payment.dto.DecodedQrResponse;
import com.payverse.payment.dto.DynamicQrRequest;
import com.payverse.payment.dto.QrResponse;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.mapper.PaymentMapper;
import com.payverse.payment.service.QrService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/qr")
@Tag(name = "QR codes")
public class QrController {

    private final QrService qrService;
    private final PaymentMapper mapper;

    public QrController(QrService qrService, PaymentMapper mapper) {
        this.qrService = qrService;
        this.mapper = mapper;
    }

    @Operation(summary = "My static QR (any amount, reusable)")
    @PostMapping("/static")
    public QrResponse staticQr(AuthenticatedUser user) {
        return mapper.toResponse(qrService.staticQr(user.userId()));
    }

    @Operation(summary = "A dynamic QR for a fixed amount (valid up to 60 minutes)")
    @PostMapping("/dynamic")
    public ResponseEntity<QrResponse> dynamicQr(@Valid @RequestBody DynamicQrRequest request, AuthenticatedUser user) {
        Duration validity = Duration.ofMinutes(request.validityMinutes() == null ? 15 : request.validityMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(
                qrService.dynamicQr(user.userId(), Money.inr(request.amount()), request.note(), validity)));
    }

    @Operation(summary = "QR image (PNG)")
    @GetMapping(path = "/{id}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable UUID id, AuthenticatedUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(qrService.png(user.userId(), id));
    }

    @Operation(summary = "Decode a scanned UPI QR payload")
    @PostMapping("/decode")
    public DecodedQrResponse decode(@Valid @RequestBody DecodeQrRequest request) {
        return mapper.toResponse(qrService.decode(request.payload()));
    }

    @Operation(summary = "Decode a QR from an uploaded image")
    @PostMapping(path = "/decode-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DecodedQrResponse decodeImage(@RequestPart("image") MultipartFile image) {
        try {
            return mapper.toResponse(qrService.decodeImage(image.getBytes()));
        } catch (IOException e) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_QR, "Unreadable image");
        }
    }
}
