package com.wealthview.core.projection.dto;

import com.wealthview.persistence.entity.ProjectionScenarioEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ScenarioResponse(
        UUID id,
        String name,
        LocalDate retirementDate,
        Integer endAge,
        BigDecimal inflationRate,
        String paramsJson,
        List<ProjectionAccountResponse> accounts,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ScenarioResponse from(ProjectionScenarioEntity entity) {
        var accounts = entity.getAccounts().stream()
                .map(ProjectionAccountResponse::from)
                .toList();
        return new ScenarioResponse(
                entity.getId(),
                entity.getName(),
                entity.getRetirementDate(),
                entity.getEndAge(),
                entity.getInflationRate(),
                entity.getParamsJson(),
                accounts,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
