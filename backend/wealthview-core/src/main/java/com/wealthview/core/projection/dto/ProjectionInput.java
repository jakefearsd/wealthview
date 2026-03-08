package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProjectionInput(
        UUID scenarioId,
        String scenarioName,
        LocalDate retirementDate,
        Integer endAge,
        BigDecimal inflationRate,
        String paramsJson,
        List<ProjectionAccountInput> accounts,
        SpendingProfileInput spendingProfile,
        Integer referenceYear
) {
    public ProjectionInput(UUID scenarioId, String scenarioName, LocalDate retirementDate,
                           Integer endAge, BigDecimal inflationRate, String paramsJson,
                           List<ProjectionAccountInput> accounts, SpendingProfileInput spendingProfile) {
        this(scenarioId, scenarioName, retirementDate, endAge, inflationRate,
                paramsJson, accounts, spendingProfile, null);
    }
}
