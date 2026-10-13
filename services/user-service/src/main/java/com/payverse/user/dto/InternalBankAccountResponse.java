package com.payverse.user.dto;

import java.util.UUID;

public record InternalBankAccountResponse(UUID id, UUID userId, String maskedAccountNumber, String ifsc, String holderName,
                                          boolean primary) {
}
