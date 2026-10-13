package com.payverse.user.application.port;

import com.payverse.user.domain.Beneficiary;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository {

    List<Beneficiary> findActive(UUID userId);

    Optional<Beneficiary> findActive(UUID userId, UUID beneficiaryId);

    boolean existsActive(UUID userId, Beneficiary.Type type, String value);

    Beneficiary save(Beneficiary beneficiary);

    void remove(UUID beneficiaryId, Instant now);
}
