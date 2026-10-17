package com.payverse.bill.service;

import com.payverse.bill.entity.Biller;
import com.payverse.bill.model.FetchedBill;
import com.payverse.common.money.Money;

import java.util.UUID;

/**
 * A biller aggregator (BBPS-style). {@code pay} must be idempotent per {@code paymentId}: our retries
 * after a timeout carry the same id. {@link MockBillerAdapter} simulates it.
 */
public interface BillerGateway {

    /** The biller refused the payment for a business reason (money must be refunded). */
    class BillerDeclinedException extends RuntimeException {
        public BillerDeclinedException(String message) {
            super(message);
        }
    }

    /** No answer / technical failure: try again later with the same payment id. */
    class BillerUnavailableException extends RuntimeException {
        public BillerUnavailableException(String message) {
            super(message);
        }
    }

    /** Thrown by fetch when the biller does not know the account. */
    class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String message) {
            super(message);
        }
    }

    FetchedBill fetch(Biller biller, String accountRef);

    /** @return the biller's reference */
    String pay(Biller biller, String accountRef, Money amount, UUID paymentId);
}
