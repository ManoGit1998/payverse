package com.payverse.fraud.repository;

import com.payverse.fraud.entity.Flag;
import com.payverse.fraud.entity.FlagStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FlagRepository extends JpaRepository<Flag, UUID> {

    Page<Flag> findByStatusOrderByCreatedAtDesc(FlagStatus status, Pageable pageable);

    Page<Flag> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
