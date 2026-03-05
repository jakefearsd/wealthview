package com.wealthview.core.tenant.dto;

import com.wealthview.persistence.entity.TenantEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        OffsetDateTime createdAt
) {
    public static TenantResponse from(TenantEntity entity) {
        return new TenantResponse(entity.getId(), entity.getName(), entity.getCreatedAt());
    }
}
