package com.payverse.transaction.repository;

import com.payverse.transaction.entity.SplitBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SplitBillRepository extends JpaRepository<SplitBill, UUID> {
}
