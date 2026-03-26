package com.wealthview.core.tenant.dto;

import com.wealthview.persistence.entity.UserEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String email,
        String role,
        UUID tenantId,
        String tenantName,
        boolean isActive,
        OffsetDateTime createdAt
) {
    public static AdminUserResponse from(UserEntity entity) {
        return new AdminUserResponse(
                entity.getId(),
                entity.getEmail(),
                entity.getRole(),
                entity.getTenant().getId(),
                entity.getTenant().getName(),
                entity.isActive(),
                entity.getCreatedAt()
        );
    }
}
