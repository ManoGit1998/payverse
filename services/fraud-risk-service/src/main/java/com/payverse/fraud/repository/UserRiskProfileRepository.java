package com.payverse.fraud.repository;

import com.payverse.fraud.entity.UserRiskProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRiskProfileRepository extends JpaRepository<UserRiskProfile, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserRiskProfile p where p.userId = :userId")
    Optional<UserRiskProfile> lockById(@Param("userId") UUID userId);
}
