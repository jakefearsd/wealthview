package com.wealthview.core.audit;

import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID tenantId,
        UUID userId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> details
) {
}
