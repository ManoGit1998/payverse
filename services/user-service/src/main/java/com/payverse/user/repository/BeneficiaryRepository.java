package com.payverse.user.repository;

import com.payverse.user.entity.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    List<Beneficiary> findByUserIdAndRemovedAtIsNullOrderByCreatedAtDesc(UUID userId);

    Optional<Beneficiary> findByIdAndUserIdAndRemovedAtIsNull(UUID id, UUID userId);

    boolean existsByUserIdAndValueHashAndRemovedAtIsNull(UUID userId, String valueHash);

    long countByUserIdAndRemovedAtIsNull(UUID userId);
}
