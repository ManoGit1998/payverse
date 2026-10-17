package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.transaction.client.AuthClient;
import com.payverse.transaction.client.PaymentClient;
import com.payverse.transaction.client.UserClient;
import com.payverse.transaction.entity.PayeeType;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.exception.TransactionErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Turns "how the user addressed the payee" into a PayVerse user:
 * MOBILE → auth-service lookup, VPA → payment-service (UPI switch), QR → payment-service decode,
 * USER → already known (internal callers such as collect approvals and money requests).
 */
@Component
public class PayeeResolver {

    /** {@code value} is the mobile / VPA / QR payload, or the user id for USER. */
    public record Target(PayeeType type, String value, String ref) {
    }

    public record Resolution(Transaction.Payee payee, Money amount) {
    }

    private final AuthClient authClient;
    private final PaymentClient paymentClient;
    private final UserClient userClient;

    public PayeeResolver(AuthClient authClient, PaymentClient paymentClient, UserClient userClient) {
        this.authClient = authClient;
        this.paymentClient = paymentClient;
        this.userClient = userClient;
    }

    public Resolution resolve(Target target, Money requestedAmount) {
        if (target == null || target.type() == null || target.value() == null || target.value().isBlank()) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_PAYEE, "payee.type and payee.value are required");
        }
        return switch (target.type()) {
            case MOBILE -> {
                AuthClient.UserLookup user = authClient.lookupByMobile(target.value());
                yield new Resolution(new Transaction.Payee(user.userId(), PayeeType.MOBILE, user.mobileMasked(), name(user.userId())),
                        requireAmount(requestedAmount));
            }
            case VPA -> {
                PaymentClient.ResolvedVpa vpa = paymentClient.resolveVpa(target.value());
                yield new Resolution(new Transaction.Payee(vpa.userId(), PayeeType.VPA, vpa.vpa(), vpa.payeeName()),
                        requireAmount(requestedAmount));
            }
            case QR -> {
                PaymentClient.DecodedQr qr = paymentClient.decodeQr(target.value());
                Money amount = requestedAmount;
                if (qr.amount() != null) {
                    Money qrAmount = Money.inr(new BigDecimal(qr.amount().amount()));
                    if (requestedAmount != null && !requestedAmount.equals(qrAmount)) {
                        throw new PayverseException(TransactionErrorCode.TXN_QR_AMOUNT_MISMATCH, null,
                                Map.of("qrAmount", qrAmount.toPlainString()));
                    }
                    amount = qrAmount;
                }
                yield new Resolution(new Transaction.Payee(qr.payeeUserId(), PayeeType.QR, qr.vpa(), qr.payeeName()), requireAmount(amount));
            }
            case USER -> {
                UUID userId = UUID.fromString(target.value());
                yield new Resolution(new Transaction.Payee(userId, PayeeType.USER, target.ref() == null ? "PayVerse user" : target.ref(),
                        name(userId)), requireAmount(requestedAmount));
            }
        };
    }

    private String name(UUID userId) {
        return userClient.summary(userId).map(UserClient.UserSummary::fullName).orElse(null);
    }

    private static Money requireAmount(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_AMOUNT, "amount is required");
        }
        return amount;
    }
}
