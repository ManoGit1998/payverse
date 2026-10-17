package com.payverse.payment.repository;

import com.payverse.payment.entity.CollectRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CollectRequestRepository extends JpaRepository<CollectRequest, UUID> {

    Page<CollectRequest> findByPayerUserIdOrderByCreatedAtDesc(UUID payerUserId, Pageable pageable);

    Page<CollectRequest> findByPayeeUserIdOrderByCreatedAtDesc(UUID payeeUserId, Pageable pageable);

    List<CollectRequest> findByTransactionId(UUID transactionId);
}
