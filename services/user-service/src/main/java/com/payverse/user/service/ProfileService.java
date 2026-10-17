package com.payverse.user.service;

import com.payverse.user.entity.KycTier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import com.payverse.common.error.PayverseException;
import com.payverse.user.entity.UserProfile;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Profiles are created from {@code auth.user-registered}, or lazily on first access (whichever comes first). */
@Service
public class ProfileService {

    private final UserProfileRepository profiles;
    private final Clock clock;

    public ProfileService(UserProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    public record UpdateProfile(String fullName, String email, LocalDate dateOfBirth) {
    }

    @Transactional
    public UserProfile getOrCreate(UUID userId) {
        return profiles.findById(userId).orElseGet(() -> {
            profiles.insertIfAbsent(userId, null, clock.instant());
            return profiles.findById(userId).orElseThrow();
        });
    }

    @Transactional(readOnly = true)
    public UserProfile get(UUID userId) {
        return profiles.findById(userId).orElseThrow(() -> new PayverseException(UserErrorCode.USER_PROFILE_NOT_FOUND, null));
    }

    @Transactional
    public UserProfile update(UUID userId, UpdateProfile command) {
        UserProfile profile = getOrCreate(userId);
        profile.updateDetails(command.fullName(), command.email(), command.dateOfBirth(), clock.instant());
        return profiles.saveAndFlush(profile);
    }

    /** Handles {@code auth.user-registered}: create the profile, or fill in the masked mobile. */
    @Transactional
    public void onUserRegistered(UUID userId, String mobileMasked) {
        profiles.insertIfAbsent(userId, mobileMasked, clock.instant());
        profiles.findById(userId).ifPresent(profile -> profile.attachMobile(mobileMasked, clock.instant()));
    }

    /** Back-office search (admin-service). */
    @Transactional(readOnly = true)
    public Page<UserProfile> search(String name, KycTier tier, int page, int size) {
        return profiles.search(name == null ? "" : name.trim(), tier, PageRequest.of(page, size));
    }
}
