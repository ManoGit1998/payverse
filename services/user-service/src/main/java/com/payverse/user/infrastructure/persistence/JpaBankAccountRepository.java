package com.payverse.user.infrastructure.persistence;

import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.user.application.port.BankAccountRepository;
import com.payverse.user.domain.BankAccount;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaBankAccountRepository implements BankAccountRepository {

    private final BankAccountJpaRepository jpa;
    private final BlindIndexer blindIndexer;

    JpaBankAccountRepository(BankAccountJpaRepository jpa, BlindIndexer blindIndexer) {
        this.jpa = jpa;
        this.blindIndexer = blindIndexer;
    }

    @Override
    public List<BankAccount> findActive(UUID userId) {
        return jpa.findByUserIdAndRemovedAtIsNull(userId).stream().map(JpaBankAccountRepository::toDomain).toList();
    }

    @Override
    public Optional<BankAccount> findActive(UUID userId, UUID accountId) {
        return jpa.findByIdAndUserIdAndRemovedAtIsNull(accountId, userId).map(JpaBankAccountRepository::toDomain);
    }

    @Override
    public boolean existsActive(UUID userId, String accountNumber) {
        return jpa.existsByUserIdAndAccountHashAndRemovedAtIsNull(userId, blindIndexer.index(accountNumber));
    }

    @Override
    public BankAccount save(BankAccount account) {
        BankAccountEntity e = jpa.findById(account.id()).orElseGet(BankAccountEntity::new);
        e.id = account.id();
        e.userId = account.userId();
        e.accountNumber = account.accountNumber();
        e.accountHash = blindIndexer.index(account.accountNumber());
        e.ifsc = account.ifsc();
        e.holderName = account.holderName();
        e.primary = account.primary();
        e.createdAt = account.createdAt();
        return toDomain(jpa.saveAndFlush(e));
    }

    @Override
    public void remove(UUID accountId, Instant now) {
        jpa.markRemoved(accountId, now);
    }

    private static BankAccount toDomain(BankAccountEntity e) {
        return new BankAccount(e.id, e.userId, e.accountNumber, e.ifsc, e.holderName, e.primary, e.createdAt);
    }
}
