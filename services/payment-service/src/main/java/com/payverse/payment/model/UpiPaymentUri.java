package com.payverse.payment.model;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.payment.exception.PaymentErrorCode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The NPCI UPI deep-link format encoded in QR codes:
 * {@code upi://pay?pa=<vpa>&pn=<name>&am=<amount>&cu=INR&tn=<note>&tr=<reference>}.
 *
 * @param payeeVpa  {@code pa}
 * @param payeeName {@code pn} (optional)
 * @param amount    {@code am} (dynamic QR only)
 * @param note      {@code tn} (optional)
 * @param reference {@code tr}: the PayVerse QR id for QRs issued here (optional)
 */
public record UpiPaymentUri(VpaHandle payeeVpa, String payeeName, Money amount, String note, String reference) {

    public String format() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pa", payeeVpa.value());
        if (payeeName != null) {
            params.put("pn", payeeName);
        }
        if (amount != null) {
            params.put("am", amount.toPlainString());
            params.put("cu", "INR");
        }
        if (note != null) {
            params.put("tn", note);
        }
        if (reference != null) {
            params.put("tr", reference);
        }
        StringJoiner query = new StringJoiner("&");
        params.forEach((k, v) -> query.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20")));
        return "upi://pay?" + query;
    }

    public static UpiPaymentUri parse(String payload) {
        try {
            URI uri = URI.create(payload == null ? "" : payload.trim());
            if (!"upi".equalsIgnoreCase(uri.getScheme()) || !"pay".equalsIgnoreCase(uri.getHost()) || uri.getRawQuery() == null) {
                throw invalid();
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            if (params.containsKey("cu") && !"INR".equals(params.get("cu"))) {
                throw invalid();
            }
            Money amount = params.containsKey("am") ? Money.inr(new BigDecimal(params.get("am"))) : null;
            if (amount != null && !amount.isPositive()) {
                throw invalid();
            }
            return new UpiPaymentUri(VpaHandle.parse(params.get("pa")), params.get("pn"), amount, params.get("tn"), params.get("tr"));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static PayverseException invalid() {
        return new PayverseException(PaymentErrorCode.PAYMENT_INVALID_QR, "Not a UPI payment QR (upi://pay?pa=...)");
    }
}
