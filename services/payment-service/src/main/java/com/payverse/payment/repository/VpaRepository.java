package com.payverse.payment.repository;

import com.payverse.payment.entity.Vpa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VpaRepository extends JpaRepository<Vpa, UUID> {

    Optional<Vpa> findByHandle(String handle);

    Optional<Vpa> findByUserIdAndDefaultVpaTrue(UUID userId);

    List<Vpa> findByUserIdOrderByCreatedAt(UUID userId);

    boolean existsByHandle(String handle);
}
