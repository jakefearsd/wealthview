package com.wealthview.core.tenant.dto;

import com.wealthview.persistence.entity.InviteCodeEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InviteCodeResponse(
        UUID id,
        String code,
        OffsetDateTime expiresAt,
        boolean consumed,
        boolean isRevoked,
        String usedByEmail,
        String createdByEmail,
        OffsetDateTime createdAt
) {
    public static InviteCodeResponse from(InviteCodeEntity entity) {
        return new InviteCodeResponse(
                entity.getId(),
                entity.getCode(),
                entity.getExpiresAt(),
                entity.isConsumed(),
                entity.isRevoked(),
                entity.getConsumedBy() != null ? entity.getConsumedBy().getEmail() : null,
                entity.getCreatedBy() != null ? entity.getCreatedBy().getEmail() : null,
                entity.getCreatedAt()
        );
    }
}
