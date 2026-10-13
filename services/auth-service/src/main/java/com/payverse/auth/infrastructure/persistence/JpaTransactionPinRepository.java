package com.payverse.auth.infrastructure.persistence;

import com.payverse.auth.application.port.TransactionPinRepository;
import com.payverse.auth.domain.TransactionPin;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaTransactionPinRepository implements TransactionPinRepository {

    private final TransactionPinJpaRepository jpa;

    JpaTransactionPinRepository(TransactionPinJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<TransactionPin> find(UUID userId) {
        return jpa.findById(userId).map(JpaTransactionPinRepository::toDomain);
    }

    @Override
    public boolean exists(UUID userId) {
        return jpa.existsById(userId);
    }

    @Override
    public TransactionPin save(TransactionPin pin) {
        TransactionPinEntity e = jpa.findById(pin.userId()).orElseGet(TransactionPinEntity::new);
        if (e.userId != null && e.version != pin.version()) {
            throw new org.springframework.dao.OptimisticLockingFailureException("PIN for user changed concurrently");
        }
        e.userId = pin.userId();
        e.pinHash = pin.hash();
        e.failedAttempts = pin.failedAttempts();
        e.lockedUntil = pin.lockedUntil();
        e.updatedAt = pin.updatedAt();
        return toDomain(jpa.saveAndFlush(e));
    }

    private static TransactionPin toDomain(TransactionPinEntity e) {
        return TransactionPin.restore(e.userId, e.pinHash, e.failedAttempts, e.lockedUntil, e.updatedAt, e.version);
    }
}
