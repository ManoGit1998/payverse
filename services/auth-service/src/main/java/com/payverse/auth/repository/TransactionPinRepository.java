package com.payverse.auth.repository;

import com.payverse.auth.entity.TransactionPin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransactionPinRepository extends JpaRepository<TransactionPin, UUID> {
}
