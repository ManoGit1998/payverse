package com.payverse.rewards.repository;

import com.payverse.rewards.entity.Referral;
import com.payverse.rewards.entity.ReferralStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    Optional<Referral> findByRefereeId(UUID refereeId);

    long countByReferrerId(UUID referrerId);

    long countByReferrerIdAndStatus(UUID referrerId, ReferralStatus status);
}
