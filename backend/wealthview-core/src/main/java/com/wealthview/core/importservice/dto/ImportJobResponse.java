package com.wealthview.core.importservice.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.wealthview.persistence.entity.ImportJobEntity;

public record ImportJobResponse(
        UUID id,
        String source,
        String status,
        int totalRows,
        int successfulRows,
        int failedRows,
        String errorMessage,
        OffsetDateTime createdAt
) {
    public static ImportJobResponse from(ImportJobEntity entity) {
        return new ImportJobResponse(
                entity.getId(),
                entity.getSource(),
                entity.getStatus(),
                entity.getTotalRows(),
                entity.getSuccessfulRows(),
                entity.getFailedRows(),
                entity.getErrorMessage(),
                entity.getCreatedAt()
        );
    }
}
