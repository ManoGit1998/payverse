package com.payverse.payment.service;

import com.payverse.common.money.Money;
import com.payverse.payment.model.VpaHandle;

import java.util.UUID;

/**
 * The UPI network (NPCI switch). {@link MockUpiSwitchAdapter} simulates it; a real PSP/switch adapter
 * can be plugged in behind this interface.
 */
public interface UpiSwitch {

    /** Resolves a VPA to a PayVerse user, or throws (unknown / unsupported handle). */
    UUID resolve(VpaHandle vpa);

    /** Settles a payment and returns the switch's retrieval reference number (RRN). Idempotent per transaction. */
    String settle(UUID transactionId, VpaHandle payer, VpaHandle payee, Money amount);
}
