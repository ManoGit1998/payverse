package com.payverse.payment.repository;

import com.payverse.payment.entity.PgOrder;
import com.payverse.payment.entity.PgOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PgOrderRepository extends JpaRepository<PgOrder, UUID> {

    Optional<PgOrder> findByIdempotencyKey(String idempotencyKey);

    Page<PgOrder> findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(PgOrderStatus status, Instant from,
                                                                                                     Instant to, Pageable pageable);
}
