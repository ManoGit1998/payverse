package com.payverse.payment.repository;

import com.payverse.payment.entity.PgOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PgOrderRepository extends JpaRepository<PgOrder, UUID> {

    Optional<PgOrder> findByIdempotencyKey(String idempotencyKey);
}
