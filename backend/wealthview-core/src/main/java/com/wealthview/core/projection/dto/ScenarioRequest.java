package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for both scenario create and update — the two endpoints take
 * an identical payload. Implements {@link ScenarioParamsSource} so the params
 * blob is serialized through {@link ScenarioParams}.
 *
 * <p>The four strategy fields are checked against exactly what their parsers recognise, because the
 * form sends stored values back on every save and a stored value the engine honours must stay
 * saveable. Every parser treats a blank value as "not set". {@code FilingStatus.fromString} and
 * {@code WithdrawalOrder.fromString} lower-case their input, so those two match case-insensitively;
 * {@code WithdrawalStrategyFactory} and the {@code fill_bracket} check compare exactly, so those do
 * not. {@code withdrawal_order} also keeps the pre-enum comma-list form
 * ({@code "roth,taxable,traditional"}), which persisted scenarios may still hold.
 */
public record ScenarioRequest(
        @NotBlank String name,
        LocalDate retirementDate,
        Integer endAge,
        @DecimalMin("-0.05") @DecimalMax("0.20") BigDecimal inflationRate,
        @Min(1900) @Max(2100) Integer birthYear,
        @DecimalMin("0") @DecimalMax("1") BigDecimal withdrawalRate,
        @Pattern(regexp = WITHDRAWAL_STRATEGY_PATTERN) String withdrawalStrategy,
        @DecimalMin("-1") @DecimalMax("1") BigDecimal dynamicCeiling,
        @DecimalMin("-1") @DecimalMax("1") BigDecimal dynamicFloor,
        @Pattern(regexp = FILING_STATUS_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE) String filingStatus,
        @DecimalMin("0") BigDecimal otherIncome,
        @DecimalMin("0") BigDecimal annualRothConversion,
        @Pattern(regexp = WITHDRAWAL_ORDER_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE) String withdrawalOrder,
        @DecimalMin("0") @DecimalMax("0.5") BigDecimal dynamicSequencingBracketRate,
        @Pattern(regexp = ROTH_CONVERSION_STRATEGY_PATTERN) String rothConversionStrategy,
        @DecimalMin("0") @DecimalMax("0.5") BigDecimal targetBracketRate,
        Integer rothConversionStartYear,
        String state,
        @DecimalMin("0") BigDecimal primaryResidencePropertyTax,
        @DecimalMin("0") BigDecimal primaryResidenceMortgageInterest,
        BigDecimal dividendYield,
        BigDecimal feeRate,
        Boolean includeDepressionYears,
        BigDecimal interestYield,
        @Min(1900) @Max(2100) Integer spouseBirthYear,
        Integer primaryDeathAge,
        Integer spouseDeathAge,
        BigDecimal survivorSpendingFactor,
        Boolean communityProperty,
        Boolean stochasticMortality,
        String primarySex,
        String spouseSex,
        Integer longevityConditionalAge,
        Integer birthMonth,
        Integer spouseBirthMonth,
        BigDecimal heirTaxRate,
        List<@Valid CreateProjectionAccountRequest> accounts,
        UUID spendingProfileId,
        Boolean useGuardrailProfile,
        List<ScenarioIncomeSourceInput> incomeSources) implements ScenarioParamsSource {

    /** Blank, or a value {@code WithdrawalStrategyFactory} recognises (case-sensitive). */
    static final String WITHDRAWAL_STRATEGY_PATTERN =
            "\\s*|fixed_percentage|dynamic_percentage|vanguard_dynamic_spending";

    /** Blank, or a value {@code FilingStatus.fromString} recognises; matched case-insensitively. */
    static final String FILING_STATUS_PATTERN = "\\s*|single|married_filing_jointly";

    /** One pool name in the legacy comma-list withdrawal order. */
    private static final String LEGACY_POOL = "\\s*(?:taxable|traditional|roth)\\s*";

    /**
     * Blank, a {@code WithdrawalOrder} token, or the legacy comma list of pool names; matched
     * case-insensitively.
     */
    static final String WITHDRAWAL_ORDER_PATTERN =
            "\\s*|taxable_first|traditional_first|roth_first|pro_rata|dynamic_sequencing|"
                    + LEGACY_POOL + "(?:," + LEGACY_POOL + ")*";

    /** Blank, or a Roth conversion strategy the engine recognises (case-sensitive). */
    static final String ROTH_CONVERSION_STRATEGY_PATTERN = "\\s*|fixed_amount|fill_bracket";

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
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                accounts, spendingProfileId, useGuardrailProfile, incomeSources);
    }

    /**
     * Back-compat convenience for callers that predate {@link #interestYield} (audit C1) but
     * already carry {@link #includeDepressionYears} (audit C10) — mirrors that field's identical
     * pattern. Defaults it to {@code null} ("not set" — {@code ScenarioParamsParser} resolves that
     * to {@code DEFAULT_INTEREST_YIELD}), so every pre-C1 positional call site keeps compiling.
     */
    // ExcessiveParameterList: mirrors the record's own 28-field canonical constructor (pre-C1
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
            BigDecimal dividendYield, BigDecimal feeRate, Boolean includeDepressionYears,
            List<CreateProjectionAccountRequest> accounts, UUID spendingProfileId,
            Boolean useGuardrailProfile, List<ScenarioIncomeSourceInput> incomeSources) {
        this(name, retirementDate, endAge, inflationRate, birthYear, withdrawalRate, withdrawalStrategy,
                dynamicCeiling, dynamicFloor, filingStatus, otherIncome, annualRothConversion, withdrawalOrder,
                dynamicSequencingBracketRate, rothConversionStrategy, targetBracketRate, rothConversionStartYear,
                state, primaryResidencePropertyTax, primaryResidenceMortgageInterest, dividendYield, feeRate,
                includeDepressionYears, null, null, null, null, null, null, null, null, null, null, null, null, null,
                accounts, spendingProfileId, useGuardrailProfile, incomeSources);
    }
}
