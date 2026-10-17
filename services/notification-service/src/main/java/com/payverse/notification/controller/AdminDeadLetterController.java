package com.payverse.notification.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.notification.dto.DeadLetterResponse;
import com.payverse.notification.dto.PageResponse;
import com.payverse.notification.entity.DeadLetterEvent;
import com.payverse.notification.mapper.NotificationMapper;
import com.payverse.notification.service.DeadLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@Tag(name = "Admin: dead letters")
@Validated
public class AdminDeadLetterController {

    private final DeadLetterService deadLetters;
    private final NotificationMapper mapper;

    public AdminDeadLetterController(DeadLetterService deadLetters, NotificationMapper mapper) {
        this.deadLetters = deadLetters;
        this.mapper = mapper;
    }

    @Operation(summary = "Dead-lettered Kafka messages from all services")
    @GetMapping
    public PageResponse<DeadLetterResponse> list(@RequestParam(defaultValue = "true") boolean onlyOpen,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<DeadLetterEvent> result = deadLetters.list(onlyOpen, page, size);
        return new PageResponse<>(result.map(mapper::toResponse).getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "Re-publish a dead letter to its original topic (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/replay")
    public DeadLetterResponse replay(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(deadLetters.replay(user.subject(), id));
    }
}
