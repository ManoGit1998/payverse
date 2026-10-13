package com.payverse.auth.api;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP as seen by the gateway (first X-Forwarded-For hop), falling back to the socket address. */
final class ClientIp {

    private ClientIp() {
    }

    static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        return request.getRemoteAddr();
    }
}
