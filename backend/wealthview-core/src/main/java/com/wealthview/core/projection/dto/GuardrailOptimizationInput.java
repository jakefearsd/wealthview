package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.mortality.MortalityTable;

/**
 * All inputs the guardrail/Monte-Carlo spending optimizer needs for one run.
 *
 * @param returnMean the request's OPTIONAL explicit growth-assumption override for the
 *         Roth-conversion simulator, in NOMINAL terms (legacy wire contract), or {@code null} when
 *         the request omits it — the normal case; the frontend never sends {@code return_mean}.
 *         Audit C4: do NOT pre-fill a default here. The engine resolves the effective rate once
 *         per run ({@code OptimizationContextBuilder.resolveReturnMean}): {@code null} derives the
 *         scenario's fee-adjusted, allocation-blended REAL return; non-null is Fisher-converted to
 *         real minus the scenario fee. Passing an already-real rate in this field would get
 *         deflated a second time.
 * @param dividendYield the scenario's configured taxable-pool dividend yield
 *         ({@code params_json.dividend_yield}), or {@code null} when the scenario doesn't set one.
 *         The MC engine falls back to the same default the deterministic engine uses (see
 *         {@code ScenarioParamsParser.DEFAULT_DIVIDEND_YIELD}).
 * @param feeRate the scenario's configured annual all-in investment fee/expense-ratio drag
 *         ({@code params_json.fee_rate}), or {@code null} when the scenario doesn't set one. The
 *         MC engine falls back to the same default the deterministic engine uses (see
 *         {@code ScenarioParamsParser.DEFAULT_FEE_RATE}).
 * @param baseYear the projection's base ("today") year — audit C7 / T7-M3: anchors the income
 *         deflation clock and the Social Security threshold deflator on CALENDAR years elapsed
 *         ({@code taxYear - baseYear}), matching the deterministic engine's {@code currentYear}
 *         (see {@code DeterministicProjectionEngine.resolveProjectionParams}), instead of the MC's
 *         own retirement-anchored year index. Resolved by {@code GuardrailProfileService} from the
 *         scenario's {@code referenceYear} (falling back to {@code LocalDate.now().getYear()}) —
 *         the same default the deterministic engine applies when unset.
 * @param includeDepressionYears audit C10: the scenario's {@code params_json.include_depression_years}
 *         opt-in (resolved to a primitive default of {@code false} by
 *         {@code ScenarioParamsParser.includeDepressionYears} before this input is built). Selects
 *         which {@code CapitalMarketAssumptionsProvider} window ({@code matrix(boolean)}) the MC
 *         block bootstrap samples from — {@code false} (every pre-existing caller) is the
 *         unchanged 1972-2025 window; {@code true} widens it to 1928-2025.
 * @param interestYield the scenario's configured taxable-pool bond/cash-sleeve interest yield
 *         ({@code params_json.interest_yield}), or {@code null} when the scenario doesn't set one.
 *         The MC engine falls back to the same default the deterministic engine uses (see
 *         {@code ScenarioParamsParser.DEFAULT_INTEREST_YIELD}). Audit C1: splits {@link
 *         #dividendYield} by the taxable pool's own allocation — the bond+cash share is taxed
 *         ORDINARY at this rate instead of at LTCG/qualified-dividend rates.
 * @param gateOnAdaptiveRules T24: when {@code true} (and {@link #maxAnnualAdjustmentRate} is
 *         positive), {@code SustainabilitySearch}'s candidate evaluation runs each candidate
 *         discretionary schedule WITH the audit-C9 simulated guardrail-adaptation rule active and
 *         gates on ITS success rate, instead of the no-adaptation success rate every prior caller
 *         used. The back-compat constructor default here is {@code false} — the byte-identical
 *         pre-T24 no-adaptation gate — but since V077 (default-to-on, user decision) production
 *         requests that OMIT the flag resolve to {@code true} at the service boundary
 *         ({@code GuardrailProfileService.buildOptimizationInput}); explicit {@code false} stays
 *         fully honored as the conservative anchor.
 *         {@link #maxAnnualAdjustmentRate} not being positive makes the rule a no-op (it cannot move
 *         spending), so the search silently falls back to the no-adaptation gate in that case too.
 *         Does not affect {@link GuardrailProfileResponse#successProbability}/
 *         {@link GuardrailProfileResponse#successProbabilityWithRules}, which are ALWAYS the
 *         no-adaptation / with-rules rates respectively on the final schedule — only which one
 *         certified the recommendation during the search.
 * @param stochasticMortality sub-project B: opts this run into the stochastic-mortality Monte
 *         Carlo mode, which samples each spouse's death year per trial from
 *         {@link #mortalityTable} instead of using the fixed death ages sub-project A resolves.
 *         {@code null}/{@code false} ⇒ byte-identical to sub-project A (the anchor); only takes
 *         effect when a household is present ({@link #spouseBirthYear} set) and
 *         {@link #mortalityTable} is non-null.
 * @param primarySex the primary's sex ({@code "male"}/{@code "female"}) used to select the
 *         sex-specific qx column of {@link #mortalityTable}. {@code null} ⇒ the blended mean of
 *         both sexes' qx ({@code MortalityTable.qx}).
 * @param spouseSex the spouse's sex, mirroring {@link #primarySex} for the spouse. {@code null} ⇒
 *         blended qx.
 * @param longevityConditionalAge the age for the "the survivor lives to this age" (the last
 *         surviving spouse reaches it) longevity-conditional success metric. {@code null} ⇒
 *         {@code 95}.
 * @param mortalityTable the loaded SSA period-life table, resolved by {@code
 *         ProjectionInputBuilder.resolveMortalityTable} ONLY when {@link #stochasticMortality} is
 *         {@code true} — {@code null} otherwise, so a toggle-off run never touches
 *         {@code mortality_rates} (part of the byte-identical-to-sub-project-A anchor).
 */
public record GuardrailOptimizationInput(
        LocalDate retirementDate,
        int birthYear,
        int endAge,
        BigDecimal inflationRate,
        List<ProjectionAccountInput> accounts,
        List<ProjectionIncomeSourceInput> incomeSources,
        BigDecimal essentialFloor,
        BigDecimal terminalBalanceTarget,
        BigDecimal returnMean,
        int trialCount,
        BigDecimal confidenceLevel,
        List<GuardrailPhaseInput> phases,
        Long seed,
        BigDecimal portfolioFloor,
        BigDecimal maxAnnualAdjustmentRate,
        int phaseBlendYears,
        int cashReserveYears,
        BigDecimal cashReturnRate,
        String filingStatus,
        String withdrawalOrder,
        boolean optimizeConversions,
        BigDecimal conversionBracketRate,
        BigDecimal rmdTargetBracketRate,
        int traditionalExhaustionBuffer,
        BigDecimal rmdBracketHeadroom,
        BigDecimal dynamicSequencingBracketRate,
        BigDecimal dividendYield,
        BigDecimal feeRate,
        int baseYear,
        boolean includeDepressionYears,
        BigDecimal interestYield,
        boolean gateOnAdaptiveRules,
        // Household task 6 (sub-project A): first-death transition inputs. All null/false ⇒ a
        // single-person run, byte-identical to pre-household behavior. spouseBirthYear present enables
        // the household modeling; death ages are the RESOLVED values (explicit override or SSA
        // default, resolved by the caller); survivorSpendingFactor null ⇒ the engine's 0.75 default;
        // communityProperty drives the joint-taxable basis step-up factor.
        @Nullable Integer spouseBirthYear,
        @Nullable Integer primaryDeathAge,
        @Nullable Integer spouseDeathAge,
        @Nullable BigDecimal survivorSpendingFactor,
        boolean communityProperty,
        // Sub-project B (stochastic mortality): null/false-safe raw params -- resolved downstream
        // by the sampler/context builder, not here (mirrors the household fields above).
        @Nullable Boolean stochasticMortality,
        @Nullable String primarySex,
        @Nullable String spouseSex,
        @Nullable Integer longevityConditionalAge,
        @Nullable MortalityTable mortalityTable
) {

    /**
     * Back-compat convenience for callers that predate {@link #baseYear} (audit C7 / T7-M3) AND
     * {@link #includeDepressionYears} (audit C10). Defaults {@code baseYear} to
     * {@code retirementDate.getYear()} — "as if retirement starts today", reproducing the OLD
     * retirement-anchored deflation/threshold clock exactly (offset 0) — and
     * {@code includeDepressionYears} to {@code false} — the unchanged default capital-market
     * window. Every pre-existing positional call site (tests, other optimizer builders) keeps
     * compiling AND behaving identically. Production ({@code GuardrailProfileService}) always uses
     * the canonical constructor with both resolved values.
     */
    // ExcessiveParameterList: mirrors the record's own 28-field canonical constructor
    // (pre-baseYear, pre-C10 shape) so existing positional call sites keep compiling unchanged.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GuardrailOptimizationInput(
            LocalDate retirementDate, int birthYear, int endAge, BigDecimal inflationRate,
            List<ProjectionAccountInput> accounts, List<ProjectionIncomeSourceInput> incomeSources,
            BigDecimal essentialFloor, BigDecimal terminalBalanceTarget, BigDecimal returnMean,
            int trialCount, BigDecimal confidenceLevel, List<GuardrailPhaseInput> phases, Long seed,
            BigDecimal portfolioFloor, BigDecimal maxAnnualAdjustmentRate, int phaseBlendYears,
            int cashReserveYears, BigDecimal cashReturnRate, String filingStatus, String withdrawalOrder,
            boolean optimizeConversions, BigDecimal conversionBracketRate, BigDecimal rmdTargetBracketRate,
            int traditionalExhaustionBuffer, BigDecimal rmdBracketHeadroom,
            BigDecimal dynamicSequencingBracketRate, BigDecimal dividendYield, BigDecimal feeRate) {
        this(retirementDate, birthYear, endAge, inflationRate, accounts, incomeSources, essentialFloor,
                terminalBalanceTarget, returnMean, trialCount, confidenceLevel, phases, seed, portfolioFloor,
                maxAnnualAdjustmentRate, phaseBlendYears, cashReserveYears, cashReturnRate, filingStatus,
                withdrawalOrder, optimizeConversions, conversionBracketRate, rmdTargetBracketRate,
                traditionalExhaustionBuffer, rmdBracketHeadroom, dynamicSequencingBracketRate, dividendYield,
                feeRate, retirementDate.getYear(), false, null, false,
                // Household task 6: pre-household callers are single-person (spouse absent).
                null, null, null, null, false,
                // Sub-project B: pre-stochastic-mortality callers keep the toggle off.
                null, null, null, null, null);
    }
}
