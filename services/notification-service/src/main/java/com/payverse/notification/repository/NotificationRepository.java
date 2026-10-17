package com.payverse.notification.repository;

import com.payverse.notification.entity.Channel;
import com.payverse.notification.entity.Notification;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByEventIdAndUserIdAndTemplateCodeAndChannel(UUID eventId, UUID userId, String templateCode,
                                                               Channel channel);

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    List<Notification> findByEventId(UUID eventId);

    @Query("""
            select n.id from Notification n
            where n.status = com.payverse.notification.entity.NotificationStatus.PENDING and n.nextAttemptAt <= :now
            order by n.nextAttemptAt
            """)
    List<UUID> findDue(@Param("now") Instant now, Pageable pageable);

    /** Row lock so two dispatcher instances never send the same notification. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id")
    Optional<Notification> lockById(@Param("id") UUID id);
}
