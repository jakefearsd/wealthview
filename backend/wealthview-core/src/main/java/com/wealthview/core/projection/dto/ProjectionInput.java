package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.household.HouseholdContext;

/**
 * The fully-resolved input the projection engines consume for one run.
 *
 * @param household Household/survivor modeling (sub-project A): resolved once by
 *                   {@code ProjectionInputBuilder} from the scenario's params (single-person via
 *                   {@link HouseholdContext#single} when no spouse is configured). {@code null}
 *                   in inputs built by callers that predate household modeling (all pre-existing
 *                   back-compat constructors below) — the engines ignore this field until the
 *                   owner-aware pool generalization (a later task) consumes it.
 */
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
        List<ProjectionPropertyInput> properties,
        @Nullable HouseholdContext household
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

    /**
     * Back-compat convenience for call sites predating {@link #household} (household modeling):
     * defaults it to {@code null} — the engines treat that identically to a resolved
     * single-person context until the owner-aware pool generalization consumes it.
     */
    public ProjectionInput(UUID scenarioId, String scenarioName, LocalDate retirementDate,
                           Integer endAge, BigDecimal inflationRate, String paramsJson,
                           List<ProjectionAccountInput> accounts, SpendingProfileInput spendingProfile,
                           Integer referenceYear, List<ProjectionIncomeSourceInput> incomeSources,
                           GuardrailSpendingInput guardrailSpending, List<ProjectionPropertyInput> properties) {
        this(scenarioId, scenarioName, retirementDate, endAge, inflationRate,
                paramsJson, accounts, spendingProfile, referenceYear, incomeSources,
                guardrailSpending, properties, null);
    }
}
