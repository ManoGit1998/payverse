package com.payverse.user.application.port;

import com.payverse.user.domain.UserProfile;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository {

    Optional<UserProfile> find(UUID userId);

    /** Saves with optimistic locking on {@link UserProfile#version()}; returns the saved state. */
    UserProfile save(UserProfile profile);

    /** Inserts only if no profile exists (safe under concurrent event + API creation). */
    void insertIfAbsent(UserProfile profile);
}
