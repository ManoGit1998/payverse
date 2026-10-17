package com.payverse.fraud.repository;

import com.payverse.fraud.entity.RiskRuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskRuleConfigRepository extends JpaRepository<RiskRuleConfig, UUID> {

    Optional<RiskRuleConfig> findByCode(String code);

    List<RiskRuleConfig> findAllByOrderByCodeAsc();
}
