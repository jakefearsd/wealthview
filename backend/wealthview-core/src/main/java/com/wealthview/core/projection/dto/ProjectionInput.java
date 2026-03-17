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
        Integer referenceYear,
        List<ProjectionIncomeSourceInput> incomeSources,
        GuardrailSpendingInput guardrailSpending,
        List<ProjectionPropertyInput> properties
) {
    public ProjectionInput(UUID scenarioId, String scenarioName, LocalDate retirementDate,
                           Integer endAge, BigDecimal inflationRate, String paramsJson,
                           List<ProjectionAccountInput> accounts, SpendingProfileInput spendingProfile) {
        this(scenarioId, scenarioName, retirementDate, endAge, inflationRate,
                paramsJson, accounts, spendingProfile, null, List.of(), null, List.of());
    }

    public ProjectionInput(UUID scenarioId, String scenarioName, LocalDate retirementDate,
                           Integer endAge, BigDecimal inflationRate, String paramsJson,
                           List<ProjectionAccountInput> accounts, SpendingProfileInput spendingProfile,
                           Integer referenceYear) {
        this(scenarioId, scenarioName, retirementDate, endAge, inflationRate,
                paramsJson, accounts, spendingProfile, referenceYear, List.of(), null, List.of());
    }

    public ProjectionInput(UUID scenarioId, String scenarioName, LocalDate retirementDate,
                           Integer endAge, BigDecimal inflationRate, String paramsJson,
                           List<ProjectionAccountInput> accounts, SpendingProfileInput spendingProfile,
                           Integer referenceYear, List<ProjectionIncomeSourceInput> incomeSources) {
        this(scenarioId, scenarioName, retirementDate, endAge, inflationRate,
                paramsJson, accounts, spendingProfile, referenceYear, incomeSources, null, List.of());
    }

    public ProjectionInput(UUID scenarioId, String scenarioName, LocalDate retirementDate,
                           Integer endAge, BigDecimal inflationRate, String paramsJson,
                           List<ProjectionAccountInput> accounts, SpendingProfileInput spendingProfile,
                           Integer referenceYear, List<ProjectionIncomeSourceInput> incomeSources,
                           GuardrailSpendingInput guardrailSpending) {
        this(scenarioId, scenarioName, retirementDate, endAge, inflationRate,
                paramsJson, accounts, spendingProfile, referenceYear, incomeSources,
                guardrailSpending, List.of());
    }
}
