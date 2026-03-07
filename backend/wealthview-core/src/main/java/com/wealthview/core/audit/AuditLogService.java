package com.wealthview.core.audit;

import com.wealthview.core.audit.dto.AuditLogResponse;
import com.wealthview.core.common.PageResponse;
import com.wealthview.persistence.repository.AuditLogRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> getAuditLogs(UUID tenantId, String entityType, Pageable pageable) {
        var page = entityType != null
                ? auditLogRepository.findByTenantIdAndEntityTypeOrderByCreatedAtDesc(tenantId, entityType, pageable)
                : auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        return PageResponse.from(page, AuditLogResponse::from);
    }
}
