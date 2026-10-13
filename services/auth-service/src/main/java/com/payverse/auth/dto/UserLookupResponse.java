package com.payverse.auth.dto;

import java.util.UUID;

public record UserLookupResponse(UUID userId, String mobileMasked) {
}
