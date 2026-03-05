package com.wealthview.core.tenant.dto;

import com.wealthview.persistence.entity.UserEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String role,
        OffsetDateTime createdAt
) {
    public static UserResponse from(UserEntity entity) {
        return new UserResponse(entity.getId(), entity.getEmail(),
                entity.getRole(), entity.getCreatedAt());
    }
}
