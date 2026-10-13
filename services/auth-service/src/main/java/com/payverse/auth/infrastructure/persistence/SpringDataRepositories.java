package com.payverse.auth.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByMobileHash(String mobileHash);
}

interface DeviceJpaRepository extends JpaRepository<DeviceEntity, UUID> {

    Optional<DeviceEntity> findByUserIdAndDeviceId(UUID userId, String deviceId);

    List<DeviceEntity> findByUserId(UUID userId);
}

interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            update RefreshTokenEntity t set t.revokedAt = :now, t.revokeReason = :reason
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Query("""
            update RefreshTokenEntity t set t.revokedAt = :now, t.revokeReason = :reason
            where t.userId = :userId and t.deviceId = :deviceId and t.revokedAt is null
            """)
    int revokeDevice(@Param("userId") UUID userId, @Param("deviceId") String deviceId, @Param("reason") String reason,
                     @Param("now") Instant now);
}

interface TransactionPinJpaRepository extends JpaRepository<TransactionPinEntity, UUID> {
}
