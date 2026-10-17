package com.payverse.notification.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.notification.dto.InboxResponse;
import com.payverse.notification.dto.NotificationResponse;
import com.payverse.notification.entity.Notification;
import com.payverse.notification.mapper.NotificationMapper;
import com.payverse.notification.service.InboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/me")
@Tag(name = "Inbox")
@Validated
public class InboxController {

    private final InboxService inbox;
    private final NotificationMapper mapper;

    public InboxController(InboxService inbox, NotificationMapper mapper) {
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @Operation(summary = "My notifications, newest first")
    @GetMapping
    public InboxResponse list(AuthenticatedUser user, @RequestParam(defaultValue = "0") @Min(0) int page,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Notification> result = inbox.inbox(user.userId(), page, size);
        return new InboxResponse(result.map(mapper::toResponse).getContent(), inbox.unread(user.userId()), page, size,
                result.getTotalElements());
    }

    @Operation(summary = "Mark a notification as read")
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(AuthenticatedUser user, @PathVariable UUID id) {
        return mapper.toResponse(inbox.markRead(user.userId(), id));
    }
}
