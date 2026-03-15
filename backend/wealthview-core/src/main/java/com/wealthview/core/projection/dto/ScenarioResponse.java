package com.wealthview.core.projection.dto;

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
        SpendingProfileResponse spendingProfile,
        GuardrailProfileSummary guardrailProfile,
        List<ScenarioIncomeSourceResponse> incomeSources,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
