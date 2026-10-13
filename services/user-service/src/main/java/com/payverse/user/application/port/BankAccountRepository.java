package com.payverse.user.application.port;

import com.payverse.user.domain.BankAccount;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository {

    List<BankAccount> findActive(UUID userId);

    Optional<BankAccount> findActive(UUID userId, UUID accountId);

    boolean existsActive(UUID userId, String accountNumber);

    BankAccount save(BankAccount account);

    void remove(UUID accountId, Instant now);
}
