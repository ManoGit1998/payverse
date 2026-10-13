package com.payverse.auth.infrastructure.persistence;

import com.payverse.auth.application.port.UserRepository;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.Role;
import com.payverse.auth.domain.User;
import com.payverse.auth.domain.UserStatus;
import com.payverse.common.web.crypto.BlindIndexer;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class JpaUserRepository implements UserRepository {

    private final UserJpaRepository jpa;
    private final BlindIndexer blindIndexer;

    JpaUserRepository(UserJpaRepository jpa, BlindIndexer blindIndexer) {
        this.jpa = jpa;
        this.blindIndexer = blindIndexer;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findById(id).map(JpaUserRepository::toDomain);
    }

    @Override
    public Optional<User> findByMobile(MobileNumber mobile) {
        return jpa.findByMobileHash(blindIndexer.index(mobile.value())).map(JpaUserRepository::toDomain);
    }

    @Override
    public User save(User user) {
        UserEntity entity = jpa.findById(user.id()).orElseGet(UserEntity::new);
        entity.id = user.id();
        entity.mobile = user.mobile().value();
        entity.mobileHash = blindIndexer.index(user.mobile().value());
        entity.status = user.status().name();
        entity.roles = user.roles().stream().map(Role::name).sorted().collect(Collectors.joining(","));
        entity.createdAt = user.createdAt();
        return toDomain(jpa.saveAndFlush(entity));
    }

    private static User toDomain(UserEntity e) {
        var roles = Arrays.stream(e.roles.split(",")).filter(s -> !s.isBlank()).map(Role::valueOf).collect(Collectors.toSet());
        return User.restore(e.id, new MobileNumber(e.mobile), UserStatus.valueOf(e.status), roles, e.createdAt, e.version);
    }
}
