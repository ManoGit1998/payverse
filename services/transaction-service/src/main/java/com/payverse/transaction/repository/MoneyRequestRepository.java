package com.payverse.transaction.repository;

import com.payverse.transaction.entity.MoneyRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MoneyRequestRepository extends JpaRepository<MoneyRequest, UUID> {

    Page<MoneyRequest> findByPayerUserIdOrderByCreatedAtDesc(UUID payerUserId, Pageable pageable);

    Page<MoneyRequest> findByRequesterUserIdOrderByCreatedAtDesc(UUID requesterUserId, Pageable pageable);

    List<MoneyRequest> findBySplitBillIdOrderByCreatedAt(UUID splitBillId);
}
