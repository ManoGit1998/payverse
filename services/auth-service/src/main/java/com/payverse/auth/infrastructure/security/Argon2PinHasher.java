package com.payverse.auth.infrastructure.security;

import com.payverse.auth.domain.Pin;
import com.payverse.auth.domain.PinHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id PIN hashing (Spring Security 5.8+ defaults: 16 MB memory, 2 iterations, random salt). */
@Component
class Argon2PinHasher implements PinHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Override
    public String hash(Pin pin) {
        return encoder.encode(pin.value());
    }

    @Override
    public boolean matches(Pin pin, String hash) {
        return encoder.matches(pin.value(), hash);
    }
}
