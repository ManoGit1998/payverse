package com.payverse.wallet.repository;

import com.payverse.wallet.entity.LimitType;
import com.payverse.wallet.entity.LimitUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface LimitUsageRepository extends JpaRepository<LimitUsage, UUID> {

    Optional<LimitUsage> findByWalletIdAndLimitTypeAndPeriodStart(UUID walletId, LimitType type, LocalDate periodStart);
}
