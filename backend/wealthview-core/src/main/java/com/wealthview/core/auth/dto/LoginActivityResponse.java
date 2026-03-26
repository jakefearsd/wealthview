package com.wealthview.core.auth.dto;

import com.wealthview.persistence.entity.LoginActivityEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LoginActivityResponse(
        String userEmail,
        UUID tenantId,
        boolean success,
        String ipAddress,
        OffsetDateTime createdAt
) {
    public static LoginActivityResponse from(LoginActivityEntity entity) {
        return new LoginActivityResponse(
                entity.getUserEmail(),
                entity.getTenantId(),
                entity.isSuccess(),
                entity.getIpAddress(),
                entity.getCreatedAt()
        );
    }
}
