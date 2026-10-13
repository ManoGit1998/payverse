package com.payverse.user.dto;

import java.time.Instant;
import java.util.UUID;

public record BankAccountResponse(UUID id, String maskedAccountNumber, String ifsc, String bankCode, String holderName,
                                  boolean primary, Instant createdAt) {
}
