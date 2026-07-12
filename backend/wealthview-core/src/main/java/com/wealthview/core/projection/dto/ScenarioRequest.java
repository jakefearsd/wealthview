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
        BigDecimal feeRate,
        Boolean includeDepressionYears,
        List<CreateProjectionAccountRequest> accounts,
        UUID spendingProfileId,
        Boolean useGuardrailProfile,
        List<ScenarioIncomeSourceInput> incomeSources) implements ScenarioParamsSource {

    /**
     * Back-compat convenience for callers that predate {@link #includeDepressionYears} (audit
     * C10) — mirrors {@code GuardrailOptimizationInput}'s identical pattern. Defaults it to
     * {@code null} ("not set" — {@code ScenarioParamsParser} resolves that to {@code false}, the
     * unchanged default window), so every pre-existing positional call site keeps compiling.
     */
    // ExcessiveParameterList: mirrors the record's own 27-field canonical constructor (pre-C10
    // shape) so existing positional call sites keep compiling unchanged.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public ScenarioRequest(
            String name, LocalDate retirementDate, Integer endAge, BigDecimal inflationRate,
            Integer birthYear, BigDecimal withdrawalRate, String withdrawalStrategy,
            BigDecimal dynamicCeiling, BigDecimal dynamicFloor, String filingStatus,
            BigDecimal otherIncome, BigDecimal annualRothConversion, String withdrawalOrder,
            BigDecimal dynamicSequencingBracketRate, String rothConversionStrategy,
            BigDecimal targetBracketRate, Integer rothConversionStartYear, String state,
            BigDecimal primaryResidencePropertyTax, BigDecimal primaryResidenceMortgageInterest,
            BigDecimal dividendYield, BigDecimal feeRate,
            List<CreateProjectionAccountRequest> accounts, UUID spendingProfileId,
            Boolean useGuardrailProfile, List<ScenarioIncomeSourceInput> incomeSources) {
        this(name, retirementDate, endAge, inflationRate, birthYear, withdrawalRate, withdrawalStrategy,
                dynamicCeiling, dynamicFloor, filingStatus, otherIncome, annualRothConversion, withdrawalOrder,
                dynamicSequencingBracketRate, rothConversionStrategy, targetBracketRate, rothConversionStartYear,
                state, primaryResidencePropertyTax, primaryResidenceMortgageInterest, dividendYield, feeRate,
                null, accounts, spendingProfileId, useGuardrailProfile, incomeSources);
    }
}
