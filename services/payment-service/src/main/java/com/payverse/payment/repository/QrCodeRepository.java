package com.payverse.payment.repository;

import com.payverse.payment.entity.QrCode;
import com.payverse.payment.entity.QrType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QrCodeRepository extends JpaRepository<QrCode, UUID> {

    Optional<QrCode> findFirstByUserIdAndTypeAndVpa(UUID userId, QrType type, String vpa);
}
