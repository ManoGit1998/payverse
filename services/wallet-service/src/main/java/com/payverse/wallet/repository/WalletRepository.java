package com.payverse.wallet.repository;

import com.payverse.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(UUID userId);

    /** Scalar lookup that does not load the entity (used before taking the wallet lock). */
    @Query("select w.id from Wallet w where w.userId = :userId")
    Optional<UUID> findIdByUserId(@Param("userId") UUID userId);
}
