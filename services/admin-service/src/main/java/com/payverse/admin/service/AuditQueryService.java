package com.payverse.admin.service;

import com.payverse.admin.model.AuditEntry;
import com.payverse.admin.model.AuditFilter;
import com.payverse.admin.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The admin audit trail (append-only, written by every admin action). */
@Service
public class AuditQueryService {

    private final AuditLogRepository auditLog;

    public AuditQueryService(AuditLogRepository auditLog) {
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> find(AuditFilter filter, int page, int size) {
        return auditLog.find(filter, page, size);
    }
}
