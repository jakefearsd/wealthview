package com.wealthview.core.config.dto;

import com.wealthview.persistence.entity.SystemConfigEntity;

import java.time.OffsetDateTime;

public record SystemConfigResponse(
        String key,
        String value,
        OffsetDateTime updatedAt
) {
    public static SystemConfigResponse from(SystemConfigEntity entity) {
        return new SystemConfigResponse(entity.getKey(), entity.getValue(), entity.getUpdatedAt());
    }

    public static SystemConfigResponse masked(SystemConfigEntity entity, String maskedValue) {
        return new SystemConfigResponse(entity.getKey(), maskedValue, entity.getUpdatedAt());
    }
}
