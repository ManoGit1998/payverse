package com.payverse.notification.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.notification.dto.NotificationResponse;
import com.payverse.notification.dto.TemplateResponse;
import com.payverse.notification.dto.TemplateUpdateRequest;
import com.payverse.notification.entity.Channel;
import com.payverse.notification.mapper.NotificationMapper;
import com.payverse.notification.service.InboxService;
import com.payverse.notification.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin: templates")
public class AdminTemplateController {

    private final TemplateService templates;
    private final InboxService inbox;
    private final NotificationMapper mapper;

    public AdminTemplateController(TemplateService templates, InboxService inbox, NotificationMapper mapper) {
        this.templates = templates;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @Operation(summary = "List templates")
    @GetMapping("/templates")
    public List<TemplateResponse> list() {
        return templates.all().stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "Create or update the template for a code and channel (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/templates/{code}/{channel}")
    public TemplateResponse update(@PathVariable String code, @PathVariable Channel channel,
                                   @Valid @RequestBody TemplateUpdateRequest request, AuthenticatedUser user) {
        return mapper.toResponse(templates.update(user.subject(), code, channel, request.subject(), request.body(),
                request.active() == null || request.active()));
    }

    @Operation(summary = "Requeue a DEAD notification (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/notifications/{id}/retry")
    public NotificationResponse retry(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(inbox.retry(user.subject(), id));
    }
}
