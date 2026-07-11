package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request body for both scenario create and update — the two endpoints take
 * an identical payload. Implements {@link ScenarioParamsSource} so the params
 * blob is serialized through {@link ScenarioParams}.
 */
public record ScenarioRequest(
        String name,
        LocalDate retirementDate,
        Integer endAge,
        BigDecimal inflationRate,
        Integer birthYear,
        BigDecimal withdrawalRate,
        String withdrawalStrategy,
        BigDecimal dynamicCeiling,
        BigDecimal dynamicFloor,
        String filingStatus,
        BigDecimal otherIncome,
        BigDecimal annualRothConversion,
        String withdrawalOrder,
        BigDecimal dynamicSequencingBracketRate,
        String rothConversionStrategy,
        BigDecimal targetBracketRate,
        Integer rothConversionStartYear,
        String state,
        BigDecimal primaryResidencePropertyTax,
        BigDecimal primaryResidenceMortgageInterest,
        BigDecimal dividendYield,
        List<CreateProjectionAccountRequest> accounts,
        UUID spendingProfileId,
        Boolean useGuardrailProfile,
        List<ScenarioIncomeSourceInput> incomeSources) implements ScenarioParamsSource {
}
