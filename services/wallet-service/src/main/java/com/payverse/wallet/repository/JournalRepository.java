package com.payverse.wallet.repository;

import com.payverse.wallet.entity.Journal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<Journal, UUID> {

    Optional<Journal> findByIdempotencyKey(String idempotencyKey);

    Optional<Journal> findByReversesJournalId(UUID journalId);
}
