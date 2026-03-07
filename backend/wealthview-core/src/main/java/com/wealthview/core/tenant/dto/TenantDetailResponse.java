package com.wealthview.core.tenant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantDetailResponse(
        UUID id,
        String name,
        boolean isActive,
        long userCount,
        long accountCount,
        OffsetDateTime createdAt
) {
}
