package com.wealthview.core.transaction.dto;

import com.wealthview.persistence.entity.TransactionEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        LocalDate date,
        String type,
        String symbol,
        BigDecimal quantity,
        BigDecimal amount,
        OffsetDateTime createdAt
) {
    public static TransactionResponse from(TransactionEntity entity) {
        return new TransactionResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getDate(),
                entity.getType(),
                entity.getSymbol(),
                entity.getQuantity(),
                entity.getAmount(),
                entity.getCreatedAt()
        );
    }
}
