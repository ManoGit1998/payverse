package com.payverse.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProfileJpaRepository extends JpaRepository<ProfileEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO profiles (user_id, mobile_masked, kyc_tier, created_at, updated_at, version)
            VALUES (:userId, :mobileMasked, 'NONE', :now, :now, 0)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("mobileMasked") String mobileMasked, @Param("now") Instant now);
}

interface KycRecordJpaRepository extends JpaRepository<KycRecordEntity, UUID> {

    List<KycRecordEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByIdNumberHashAndStatusAndUserIdNot(String idNumberHash, String status, UUID userId);
}

interface BankAccountJpaRepository extends JpaRepository<BankAccountEntity, UUID> {

    List<BankAccountEntity> findByUserIdAndRemovedAtIsNull(UUID userId);

    Optional<BankAccountEntity> findByIdAndUserIdAndRemovedAtIsNull(UUID id, UUID userId);

    boolean existsByUserIdAndAccountHashAndRemovedAtIsNull(UUID userId, String accountHash);

    @Modifying
    @Query("update BankAccountEntity b set b.removedAt = :now, b.primary = false where b.id = :id")
    int markRemoved(@Param("id") UUID id, @Param("now") Instant now);
}

interface BeneficiaryJpaRepository extends JpaRepository<BeneficiaryEntity, UUID> {

    List<BeneficiaryEntity> findByUserIdAndRemovedAtIsNull(UUID userId);

    Optional<BeneficiaryEntity> findByIdAndUserIdAndRemovedAtIsNull(UUID id, UUID userId);

    boolean existsByUserIdAndTypeAndValueHashAndRemovedAtIsNull(UUID userId, String type, String valueHash);

    @Modifying
    @Query("update BeneficiaryEntity b set b.removedAt = :now where b.id = :id")
    int markRemoved(@Param("id") UUID id, @Param("now") Instant now);
}
