package com.payverse.payment.service;

import com.payverse.common.error.PayverseException;
import com.payverse.payment.client.UserClient;
import com.payverse.payment.entity.Vpa;
import com.payverse.payment.exception.PaymentErrorCode;
import com.payverse.payment.model.VpaHandle;
import com.payverse.payment.repository.VpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** UPI IDs: a default one per user (created on registration) plus up to two custom ones. */
@Service
public class VpaService {

    public record ResolvedVpa(String vpa, UUID userId, String payeeName) {
    }

    private final VpaRepository vpas;
    private final UpiSwitch upiSwitch;
    private final UserClient userClient;
    private final Clock clock;

    public VpaService(VpaRepository vpas, UpiSwitch upiSwitch, UserClient userClient, Clock clock) {
        this.vpas = vpas;
        this.upiSwitch = upiSwitch;
        this.userClient = userClient;
        this.clock = clock;
    }

    /** Idempotent: returns the default VPA, creating it if needed. */
    @Transactional
    public Vpa ensureDefault(UUID userId) {
        return vpas.findByUserIdAndDefaultVpaTrue(userId).orElseGet(() -> {
            try {
                return vpas.saveAndFlush(Vpa.create(userId, Vpa.defaultHandleFor(userId), true, clock.instant()));
            } catch (DataIntegrityViolationException raced) {
                return vpas.findByUserIdAndDefaultVpaTrue(userId).orElseThrow(() -> raced);
            }
        });
    }

    @Transactional
    public Vpa create(UUID userId, String localPart) {
        ensureDefault(userId);
        VpaHandle handle = VpaHandle.payverse(localPart);
        if (vpas.findByUserIdOrderByCreatedAt(userId).size() >= Vpa.MAX_PER_USER) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_VPA_LIMIT, "Up to " + Vpa.MAX_PER_USER + " UPI IDs");
        }
        if (vpas.existsByHandle(handle.value())) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_VPA_TAKEN, null);
        }
        try {
            return vpas.saveAndFlush(Vpa.create(userId, handle, false, clock.instant()));
        } catch (DataIntegrityViolationException raced) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_VPA_TAKEN, null);
        }
    }

    @Transactional
    public List<Vpa> list(UUID userId) {
        ensureDefault(userId);
        return vpas.findByUserIdOrderByCreatedAt(userId);
    }

    /** Resolves through the UPI switch (MOCK: @payverse only) and adds the payee's name for confirmation screens. */
    @Transactional(readOnly = true)
    public ResolvedVpa resolve(String raw) {
        VpaHandle handle = VpaHandle.parse(raw);
        UUID userId = upiSwitch.resolve(handle);
        return new ResolvedVpa(handle.value(), userId, userClient.displayName(userId).orElse("PayVerse user"));
    }

    @Transactional(readOnly = true)
    public Vpa require(String raw) {
        return vpas.findByHandle(VpaHandle.parse(raw).value()).filter(Vpa::isActive)
                .orElseThrow(() -> new PayverseException(PaymentErrorCode.PAYMENT_VPA_NOT_FOUND, null));
    }
}
