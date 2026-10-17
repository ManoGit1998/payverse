package com.payverse.payment.entity;

import com.payverse.payment.model.VpaHandle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A UPI ID owned by a PayVerse user. Every user has exactly one default VPA. */
@Entity
@Table(name = "vpas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vpa {

    public static final int MAX_PER_USER = 3;

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String handle;

    @Column(name = "is_default", nullable = false)
    private boolean defaultVpa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VpaStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static Vpa create(UUID userId, VpaHandle handle, boolean isDefault, Instant now) {
        Vpa vpa = new Vpa();
        vpa.id = UUID.randomUUID();
        vpa.userId = userId;
        vpa.handle = handle.value();
        vpa.defaultVpa = isDefault;
        vpa.status = VpaStatus.ACTIVE;
        vpa.createdAt = now;
        return vpa;
    }

    /** The system-generated default handle, derived from the user id (stable and unique). */
    public static VpaHandle defaultHandleFor(UUID userId) {
        return new VpaHandle("pv" + userId.toString().replace("-", "").substring(0, 10) + "@" + VpaHandle.PAYVERSE_PSP);
    }

    public VpaHandle getVpaHandle() {
        return new VpaHandle(handle);
    }

    public boolean isActive() {
        return status == VpaStatus.ACTIVE;
    }
}
