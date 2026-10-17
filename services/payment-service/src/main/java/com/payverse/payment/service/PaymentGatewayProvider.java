package com.payverse.payment.service;

import com.payverse.common.money.Money;

/** A card/UPI/net-banking acquirer ({@link MockPaymentGatewayAdapter} locally). */
public interface PaymentGatewayProvider {

    sealed interface Charge {
        record Approved(String reference) implements Charge {
        }

        record Declined(String reason) implements Charge {
        }
    }

    String name();

    Charge charge(Money amount, String method, String idempotencyKey);
}
