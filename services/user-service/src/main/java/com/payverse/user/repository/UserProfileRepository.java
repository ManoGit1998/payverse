package com.payverse.user.repository;

import com.payverse.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    /** Inserts a NONE-tier profile unless one exists (safe under concurrent event + API creation). */
    @Modifying
    @Query(value = """
            INSERT INTO profiles (user_id, mobile_masked, kyc_tier, created_at, updated_at, version)
            VALUES (:userId, :mobileMasked, 'NONE', :now, :now, 0)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("mobileMasked") String mobileMasked, @Param("now") Instant now);
}
