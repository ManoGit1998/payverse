package com.payverse.user.application;

import com.payverse.user.application.port.ProfileRepository;
import com.payverse.user.domain.UserProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Profiles are created from {@code auth.user-registered}, or lazily on first access (whichever comes first). */
@Service
public class ProfileService {

    private final ProfileRepository profiles;
    private final Clock clock;

    public ProfileService(ProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    public record UpdateProfile(String fullName, String email, LocalDate dateOfBirth) {
    }

    @Transactional
    public UserProfile getOrCreate(UUID userId) {
        return profiles.find(userId).orElseGet(() -> {
            profiles.insertIfAbsent(UserProfile.create(userId, null, clock.instant()));
            return profiles.find(userId).orElseThrow();
        });
    }

    @Transactional
    public UserProfile update(UUID userId, UpdateProfile command) {
        UserProfile current = getOrCreate(userId);
        return profiles.save(current.updateDetails(command.fullName(), command.email(), command.dateOfBirth(), clock.instant()));
    }

    /** Handles {@code auth.user-registered}: create the profile, or fill in the masked mobile. */
    @Transactional
    public void onUserRegistered(UUID userId, String mobileMasked) {
        profiles.insertIfAbsent(UserProfile.create(userId, mobileMasked, clock.instant()));
        UserProfile profile = profiles.find(userId).orElseThrow();
        if (profile.mobileMasked() == null && mobileMasked != null) {
            profiles.save(profile.withMobileMasked(mobileMasked, clock.instant()));
        }
    }
}
