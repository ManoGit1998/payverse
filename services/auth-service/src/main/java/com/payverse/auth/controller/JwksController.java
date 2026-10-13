package com.payverse.auth.controller;

import com.payverse.auth.security.SigningKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@Tag(name = "JWKS")
public class JwksController {

    private final SigningKeyService keys;

    public JwksController(SigningKeyService keys) {
        this.keys = keys;
    }

    @Operation(summary = "Public keys for verifying PayVerse access tokens")
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicJwks());
    }
}
