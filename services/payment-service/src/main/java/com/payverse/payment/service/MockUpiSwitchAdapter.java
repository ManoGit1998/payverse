package com.payverse.payment.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.payment.entity.Vpa;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.model.VpaHandle;
import com.payverse.payment.repository.VpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MOCK UPI switch. Only {@code @payverse} handles exist in the simulation: they resolve against the
 * local VPA table; handles of other PSPs ({@code @okhdfc}, {@code @ybl}, …) are rejected with
 * PAYMENT_VPA_EXTERNAL_UNSUPPORTED. RRNs are 12 digits derived from the transaction id (idempotent).
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.upi-switch.adapter", havingValue = "mock", matchIfMissing = true)
public class MockUpiSwitchAdapter implements UpiSwitch {

    private static final Logger log = LoggerFactory.getLogger(MockUpiSwitchAdapter.class);

    private final VpaRepository vpas;

    public MockUpiSwitchAdapter(VpaRepository vpas) {
        this.vpas = vpas;
        log.warn("MockUpiSwitchAdapter (MOCK) is active: only @payverse UPI IDs exist in the simulation.");
    }

    @Override
    public UUID resolve(VpaHandle vpa) {
        if (!vpa.isPayverse()) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_VPA_EXTERNAL_UNSUPPORTED, vpa.psp() + " handles are not simulated");
        }
        return vpas.findByHandle(vpa.value())
                .filter(Vpa::isActive)
                .map(Vpa::getUserId)
                .orElseThrow(() -> new PayverseException(PaymentErrorCode.PAYMENT_VPA_NOT_FOUND, null));
    }

    @Override
    public String settle(UUID transactionId, VpaHandle payer, VpaHandle payee, Money amount) {
        long digits = Math.floorMod(transactionId.getMostSignificantBits() ^ transactionId.getLeastSignificantBits(), 1_000_000_000_000L);
        String rrn = String.format("%012d", digits);
        log.info("[MOCK UPI] settled {} from {} to {} rrn {}", amount, payer.value(), payee.value(), rrn);
        return rrn;
    }
}
