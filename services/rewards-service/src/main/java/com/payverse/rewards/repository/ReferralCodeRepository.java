package com.payverse.rewards.repository;

import com.payverse.rewards.entity.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    Optional<ReferralCode> findByCode(String code);

    boolean existsByCode(String code);
}
