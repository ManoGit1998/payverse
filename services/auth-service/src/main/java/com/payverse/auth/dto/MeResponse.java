package com.payverse.auth.dto;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID userId, List<String> roles, String deviceId, boolean pinSet) {
}
