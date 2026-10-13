package com.payverse.auth.application.port;

import com.payverse.auth.domain.TransactionPin;

import java.util.Optional;
import java.util.UUID;

public interface TransactionPinRepository {

    Optional<TransactionPin> find(UUID userId);

    boolean exists(UUID userId);

    TransactionPin save(TransactionPin pin);
}
