package com.payverse.notification.repository;

import com.payverse.notification.entity.DeadLetterEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeadLetterRepository extends JpaRepository<DeadLetterEvent, UUID> {

    boolean existsByDltTopicAndPartitionNoAndOffsetNo(String dltTopic, int partitionNo, long offsetNo);

    Page<DeadLetterEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);

    Page<DeadLetterEvent> findByReplayedAtIsNullOrderByReceivedAtDesc(Pageable pageable);
}
