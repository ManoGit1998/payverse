package com.payverse.auth.domain;

import com.payverse.common.error.PayverseException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** An authenticated identity: one per verified mobile number. */
public final class User {

    private final UUID id;
    private final MobileNumber mobile;
    private UserStatus status;
    private Set<Role> roles;
    private final Instant createdAt;
    private final long version;

    private User(UUID id, MobileNumber mobile, UserStatus status, Set<Role> roles, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.mobile = Objects.requireNonNull(mobile);
        this.status = Objects.requireNonNull(status);
        this.roles = roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
        this.roles.add(Role.USER);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.version = version;
    }

    public static User register(MobileNumber mobile, Set<Role> roles, Instant now) {
        return new User(UUID.randomUUID(), mobile, UserStatus.ACTIVE, roles, now, 0);
    }

    public static User restore(UUID id, MobileNumber mobile, UserStatus status, Set<Role> roles, Instant createdAt, long version) {
        return new User(id, mobile, status, roles, createdAt, version);
    }

    public void ensureActive() {
        if (status != UserStatus.ACTIVE) {
            throw new PayverseException(AuthErrorCode.AUTH_USER_BLOCKED, null);
        }
    }

    /** Re-applies configured privileged roles on each login, so config changes take effect. */
    public boolean syncRoles(Set<Role> configured) {
        EnumSet<Role> next = configured.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(configured);
        next.add(Role.USER);
        boolean changed = !next.equals(roles);
        roles = next;
        return changed;
    }

    public void block() {
        status = UserStatus.BLOCKED;
    }

    public UUID id() {
        return id;
    }

    public MobileNumber mobile() {
        return mobile;
    }

    public UserStatus status() {
        return status;
    }

    public Set<Role> roles() {
        return Set.copyOf(roles);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
