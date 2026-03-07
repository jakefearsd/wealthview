package com.wealthview.core.audit.dto;

import com.wealthview.persistence.entity.AuditLogEntity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID tenantId,
        UUID userId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> details,
        OffsetDateTime createdAt
) {
    public static AuditLogResponse from(AuditLogEntity entity) {
        return new AuditLogResponse(
                entity.getId(), entity.getTenantId(), entity.getUserId(),
                entity.getAction(), entity.getEntityType(), entity.getEntityId(),
                entity.getDetails(), entity.getCreatedAt());
    }
}
