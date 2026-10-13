package com.payverse.user.infrastructure.persistence;

import com.payverse.user.application.port.ProfileRepository;
import com.payverse.user.domain.KycTier;
import com.payverse.user.domain.UserProfile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaProfileRepository implements ProfileRepository {

    private final ProfileJpaRepository jpa;

    JpaProfileRepository(ProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UserProfile> find(UUID userId) {
        return jpa.findById(userId).map(JpaProfileRepository::toDomain);
    }

    @Override
    public UserProfile save(UserProfile profile) {
        ProfileEntity e = jpa.findById(profile.userId()).orElseGet(ProfileEntity::new);
        if (e.userId != null && e.version != profile.version()) {
            throw new OptimisticLockingFailureException("Profile " + profile.userId() + " was modified concurrently");
        }
        e.userId = profile.userId();
        e.fullName = profile.fullName();
        e.email = profile.email();
        e.dateOfBirth = profile.dateOfBirth();
        e.mobileMasked = profile.mobileMasked();
        e.kycTier = profile.kycTier().name();
        e.createdAt = profile.createdAt();
        e.updatedAt = profile.updatedAt();
        return toDomain(jpa.saveAndFlush(e));
    }

    @Override
    public void insertIfAbsent(UserProfile profile) {
        jpa.insertIfAbsent(profile.userId(), profile.mobileMasked(), profile.createdAt());
    }

    private static UserProfile toDomain(ProfileEntity e) {
        return new UserProfile(e.userId, e.fullName, e.email, e.dateOfBirth, e.mobileMasked, KycTier.valueOf(e.kycTier),
                e.createdAt, e.updatedAt, e.version);
    }
}
