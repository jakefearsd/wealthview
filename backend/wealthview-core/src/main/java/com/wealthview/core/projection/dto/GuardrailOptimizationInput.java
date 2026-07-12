package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        int baseYear
) {

    /**
     * Back-compat convenience for callers that predate {@link #baseYear} (audit C7 / T7-M3).
     * Defaults it to {@code retirementDate.getYear()} — "as if retirement starts today" —
     * reproducing the OLD retirement-anchored deflation/threshold clock exactly (offset 0), so
     * every pre-existing positional call site (tests, other optimizer builders) keeps compiling
     * AND behaving identically. Production ({@code GuardrailProfileService}) always uses the
     * canonical constructor with the scenario's actual resolved base year.
     */
    // ExcessiveParameterList: mirrors the record's own 28-field canonical constructor
    // (pre-baseYear shape) so existing positional call sites keep compiling unchanged.
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
                feeRate, retirementDate.getYear());
    }
}
