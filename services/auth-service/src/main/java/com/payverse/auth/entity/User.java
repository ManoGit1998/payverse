package com.payverse.auth.entity;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.MobileNumber;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** An authenticated identity: one per verified mobile number. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    /** AES-GCM encrypted at rest. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mobile_enc", nullable = false)
    private String mobile;

    /** HMAC blind index of the mobile number, for lookups. */
    @Column(name = "mobile_hash", nullable = false, unique = true)
    private String mobileHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    /** Comma-separated role names. */
    @Column(nullable = false)
    private String roles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    public static User register(MobileNumber mobile, String mobileHash, Set<Role> roles, Instant now) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.mobile = mobile.value();
        user.mobileHash = mobileHash;
        user.status = UserStatus.ACTIVE;
        user.createdAt = now;
        user.setRoleSet(roles);
        return user;
    }

    public MobileNumber getMobileNumber() {
        return new MobileNumber(mobile);
    }

    public Set<Role> getRoleSet() {
        return Arrays.stream(roles.split(",")).filter(r -> !r.isBlank()).map(Role::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Re-applies configured privileged roles on each login, so config changes take effect. */
    public boolean syncRoles(Set<Role> configured) {
        String before = roles;
        setRoleSet(configured);
        return !roles.equals(before);
    }

    public void ensureActive() {
        if (status != UserStatus.ACTIVE) {
            throw new PayverseException(AuthErrorCode.AUTH_USER_BLOCKED, null);
        }
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public void block() {
        status = UserStatus.BLOCKED;
    }

    private void setRoleSet(Set<Role> configured) {
        EnumSet<Role> next = configured.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(configured);
        next.add(Role.USER);
        roles = next.stream().map(Role::name).sorted().collect(Collectors.joining(","));
    }
}
