package com.payverse.auth.repository;

import com.payverse.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Lookup by the HMAC blind index of the mobile number (the plaintext is encrypted). */
    Optional<User> findByMobileHash(String mobileHash);
}
