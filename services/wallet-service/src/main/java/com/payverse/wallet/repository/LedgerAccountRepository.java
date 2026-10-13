package com.payverse.wallet.repository;

import com.payverse.wallet.entity.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
}
