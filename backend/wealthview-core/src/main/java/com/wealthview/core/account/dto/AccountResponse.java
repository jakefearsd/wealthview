package com.wealthview.core.account.dto;

import com.wealthview.persistence.entity.AccountEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String type,
        String institution,
        BigDecimal balance,
        OffsetDateTime createdAt
) {
    public static AccountResponse from(AccountEntity entity, BigDecimal balance) {
        return new AccountResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getInstitution(),
                balance,
                entity.getCreatedAt()
        );
    }
}
