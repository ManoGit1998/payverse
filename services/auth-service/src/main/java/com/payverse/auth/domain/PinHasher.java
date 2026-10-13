package com.payverse.auth.domain;

/** Port for one-way PIN hashing (implemented with Argon2id in infrastructure). */
public interface PinHasher {

    String hash(Pin pin);

    boolean matches(Pin pin, String hash);
}
