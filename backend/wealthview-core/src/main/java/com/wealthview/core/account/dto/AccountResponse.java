package com.wealthview.core.account.dto;

import com.wealthview.persistence.entity.AccountEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String type,
        String institution,
        OffsetDateTime createdAt
) {
    public static AccountResponse from(AccountEntity entity) {
        return new AccountResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getInstitution(),
                entity.getCreatedAt()
        );
    }
}
