package com.payverse.auth.security;

import com.payverse.auth.model.Pin;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id PIN hashing (Spring Security 5.8+ defaults: 16 MB memory, 2 iterations, random salt). */
@Component
public class PinEncoder {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String encode(Pin pin) {
        return encoder.encode(pin.value());
    }

    public boolean matches(Pin pin, String hash) {
        return encoder.matches(pin.value(), hash);
    }
}
