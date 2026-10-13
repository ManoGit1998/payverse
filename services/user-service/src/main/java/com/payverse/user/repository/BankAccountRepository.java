package com.payverse.user.repository;

import com.payverse.user.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    List<BankAccount> findByUserIdAndRemovedAtIsNullOrderByCreatedAt(UUID userId);

    Optional<BankAccount> findByIdAndUserIdAndRemovedAtIsNull(UUID id, UUID userId);

    boolean existsByUserIdAndAccountHashAndRemovedAtIsNull(UUID userId, String accountHash);
}
