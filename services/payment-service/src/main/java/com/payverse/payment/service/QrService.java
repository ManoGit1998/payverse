package com.payverse.payment.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.payment.client.UserClient;
import com.payverse.payment.config.PaymentProperties;
import com.payverse.payment.entity.QrCode;
import com.payverse.payment.entity.QrType;
import com.payverse.payment.entity.Vpa;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.model.UpiPaymentUri;
import com.payverse.payment.repository.QrCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Static and dynamic UPI QR codes (ZXing): issue, render as PNG and decode (from text or an image). */
@Service
public class QrService {

    public record DecodedQr(UUID qrId, String vpa, UUID payeeUserId, String payeeName, Money amount, String note,
                            QrType type, Instant expiresAt) {
    }

    private static final int SIZE = 360;

    private final QrCodeRepository qrCodes;
    private final VpaService vpaService;
    private final UserClient userClient;
    private final PaymentProperties properties;
    private final Clock clock;

    public QrService(QrCodeRepository qrCodes, VpaService vpaService, UserClient userClient, PaymentProperties properties, Clock clock) {
        this.qrCodes = qrCodes;
        this.vpaService = vpaService;
        this.userClient = userClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** The user's reusable static QR for their default VPA (created once). */
    @Transactional
    public QrCode staticQr(UUID userId) {
        Vpa vpa = vpaService.ensureDefault(userId);
        return qrCodes.findFirstByUserIdAndTypeAndVpa(userId, QrType.STATIC, vpa.getHandle()).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            String payload = new UpiPaymentUri(vpa.getVpaHandle(), userClient.displayName(userId).orElse(null), null, null,
                    id.toString()).format();
            return qrCodes.save(QrCode.issue(id, userId, vpa.getHandle(), QrType.STATIC, null, null, payload, null, clock.instant()));
        });
    }

    /** A one-amount QR valid for {@code validity} (≤ the configured maximum). */
    @Transactional
    public QrCode dynamicQr(UUID userId, Money amount, String note, Duration validity) {
        if (!amount.isPositive()) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_AMOUNT, null);
        }
        Duration ttl = validity == null || validity.compareTo(properties.maxDynamicQrValidity()) > 0
                ? properties.maxDynamicQrValidity() : validity;
        Vpa vpa = vpaService.ensureDefault(userId);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String payload = new UpiPaymentUri(vpa.getVpaHandle(), userClient.displayName(userId).orElse(null), amount, note,
                id.toString()).format();
        return qrCodes.save(QrCode.issue(id, userId, vpa.getHandle(), QrType.DYNAMIC, amount, note, payload, now.plus(ttl), now));
    }

    @Transactional(readOnly = true)
    public byte[] png(UUID userId, UUID qrId) {
        QrCode qr = qrCodes.findById(qrId).orElseThrow(() -> new PayverseException(PaymentErrorCode.PAYMENT_QR_NOT_FOUND, null));
        if (!qr.getUserId().equals(userId)) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_NOT_YOURS, null);
        }
        return render(qr.getPayload());
    }

    /**
     * Decodes a scanned UPI payload. QRs issued by PayVerse are checked against our records
     * (amount, expiry); any other {@code upi://pay} QR is resolved through the UPI switch.
     */
    @Transactional(readOnly = true)
    public DecodedQr decode(String payload) {
        UpiPaymentUri uri = UpiPaymentUri.parse(payload);
        VpaService.ResolvedVpa payee = vpaService.resolve(uri.payeeVpa().value());
        QrCode issued = parseUuid(uri.reference()) == null ? null : qrCodes.findById(parseUuid(uri.reference()))
                .filter(qr -> qr.getVpa().equals(payee.vpa()))
                .orElse(null);
        if (issued != null) {
            if (issued.isExpired(clock.instant())) {
                throw new PayverseException(PaymentErrorCode.PAYMENT_QR_EXPIRED, null, Map.of("expiredAt", issued.getExpiresAt().toString()));
            }
            return new DecodedQr(issued.getId(), payee.vpa(), payee.userId(), payee.payeeName(), issued.getAmountMoney(),
                    issued.getNote(), issued.getType(), issued.getExpiresAt());
        }
        return new DecodedQr(null, payee.vpa(), payee.userId(), payee.payeeName(), uri.amount(), uri.note(),
                uri.amount() == null ? QrType.STATIC : QrType.DYNAMIC, null);
    }

    /** Decodes a QR from an uploaded image (PNG/JPEG). */
    public DecodedQr decodeImage(byte[] image) {
        try {
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(image));
            if (buffered == null) {
                throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_QR, "Unreadable image");
            }
            String text = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(buffered))))
                    .getText();
            return decode(text);
        } catch (NotFoundException e) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_QR, "No QR code found in the image");
        } catch (IOException e) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_INVALID_QR, "Unreadable image");
        }
    }

    static byte[] render(String payload) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, SIZE, SIZE,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Could not render QR code", e);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
