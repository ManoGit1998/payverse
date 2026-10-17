package com.payverse.admin.controller;

import com.payverse.admin.model.AuditEntry;
import com.payverse.admin.model.AuditFilter;
import com.payverse.admin.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@Tag(name = "Admin: audit log")
@Validated
public class AdminAuditController {

    private final AuditQueryService audit;

    public AdminAuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @Operation(summary = "Back-office actions, newest first (filter by actor, action, entity, time)")
    @GetMapping
    public List<AuditEntry> find(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                             @RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return audit.find(new AuditFilter(actor, action, entityType, entityId, from, to), page, size);
    }
}
