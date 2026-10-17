package com.payverse.payment.dto;

import java.util.UUID;

public record InternalVpaResponse(String vpa, UUID userId, String payeeName) {
}
