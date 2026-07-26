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

    /**
     * Entry point for naming components instead of counting positions. Only the components a run
     * actually uses need setting; every unset component defaults to the same no-op anchor the
     * back-compat constructor above documents -- {@code null}/{@code 0}/{@code false}, plus
     * {@link #baseYear} defaulting to {@code retirementDate.getYear()} (the "as if retirement
     * starts today" deflation clock).
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder over the canonical 42-component constructor. One setter per component, in
     * component order, so a reader can diff a call chain against the record header line by line --
     * the whole point, given how many same-typed neighbours this record has.
     */
    // TooManyFields: one field per record component is the nature of a builder for a 42-component
    // record; splitting it would defeat the purpose.
    @SuppressWarnings("PMD.TooManyFields")
    public static final class Builder {
        private LocalDate retirementDate;
        private int birthYear;
        private int endAge;
        private BigDecimal inflationRate;
        private List<ProjectionAccountInput> accounts;
        private List<ProjectionIncomeSourceInput> incomeSources;
        private BigDecimal essentialFloor;
        private BigDecimal terminalBalanceTarget;
        private BigDecimal returnMean;
        private int trialCount;
        private BigDecimal confidenceLevel;
        private List<GuardrailPhaseInput> phases;
        private Long seed;
        private BigDecimal portfolioFloor;
        private BigDecimal maxAnnualAdjustmentRate;
        private int phaseBlendYears;
        private int cashReserveYears;
        private BigDecimal cashReturnRate;
        private String filingStatus;
        private String withdrawalOrder;
        private boolean optimizeConversions;
        private BigDecimal conversionBracketRate;
        private BigDecimal rmdTargetBracketRate;
        private int traditionalExhaustionBuffer;
        private BigDecimal rmdBracketHeadroom;
        private BigDecimal dynamicSequencingBracketRate;
        private BigDecimal dividendYield;
        private BigDecimal feeRate;
        private Integer baseYear;
        private boolean includeDepressionYears;
        private BigDecimal interestYield;
        private boolean gateOnAdaptiveRules;
        private Integer spouseBirthYear;
        private Integer primaryDeathAge;
        private Integer spouseDeathAge;
        private BigDecimal survivorSpendingFactor;
        private boolean communityProperty;
        private Boolean stochasticMortality;
        private String primarySex;
        private String spouseSex;
        private Integer longevityConditionalAge;
        private MortalityTable mortalityTable;

        private Builder() {}

        public Builder retirementDate(LocalDate retirementDate) {
            this.retirementDate = retirementDate;
            return this;
        }

        public Builder birthYear(int birthYear) {
            this.birthYear = birthYear;
            return this;
        }

        public Builder endAge(int endAge) {
            this.endAge = endAge;
            return this;
        }

        public Builder inflationRate(BigDecimal inflationRate) {
            this.inflationRate = inflationRate;
            return this;
        }

        public Builder accounts(List<ProjectionAccountInput> accounts) {
            this.accounts = accounts;
            return this;
        }

        public Builder incomeSources(List<ProjectionIncomeSourceInput> incomeSources) {
            this.incomeSources = incomeSources;
            return this;
        }

        public Builder essentialFloor(BigDecimal essentialFloor) {
            this.essentialFloor = essentialFloor;
            return this;
        }

        public Builder terminalBalanceTarget(BigDecimal terminalBalanceTarget) {
            this.terminalBalanceTarget = terminalBalanceTarget;
            return this;
        }

        public Builder returnMean(BigDecimal returnMean) {
            this.returnMean = returnMean;
            return this;
        }

        public Builder trialCount(int trialCount) {
            this.trialCount = trialCount;
            return this;
        }

        public Builder confidenceLevel(BigDecimal confidenceLevel) {
            this.confidenceLevel = confidenceLevel;
            return this;
        }

        public Builder phases(List<GuardrailPhaseInput> phases) {
            this.phases = phases;
            return this;
        }

        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        public Builder portfolioFloor(BigDecimal portfolioFloor) {
            this.portfolioFloor = portfolioFloor;
            return this;
        }

        public Builder maxAnnualAdjustmentRate(BigDecimal maxAnnualAdjustmentRate) {
            this.maxAnnualAdjustmentRate = maxAnnualAdjustmentRate;
            return this;
        }

        public Builder phaseBlendYears(int phaseBlendYears) {
            this.phaseBlendYears = phaseBlendYears;
            return this;
        }

        public Builder cashReserveYears(int cashReserveYears) {
            this.cashReserveYears = cashReserveYears;
            return this;
        }

        public Builder cashReturnRate(BigDecimal cashReturnRate) {
            this.cashReturnRate = cashReturnRate;
            return this;
        }

        public Builder filingStatus(String filingStatus) {
            this.filingStatus = filingStatus;
            return this;
        }

        public Builder withdrawalOrder(String withdrawalOrder) {
            this.withdrawalOrder = withdrawalOrder;
            return this;
        }

        public Builder optimizeConversions(boolean optimizeConversions) {
            this.optimizeConversions = optimizeConversions;
            return this;
        }

        public Builder conversionBracketRate(BigDecimal conversionBracketRate) {
            this.conversionBracketRate = conversionBracketRate;
            return this;
        }

        public Builder rmdTargetBracketRate(BigDecimal rmdTargetBracketRate) {
            this.rmdTargetBracketRate = rmdTargetBracketRate;
            return this;
        }

        public Builder traditionalExhaustionBuffer(int traditionalExhaustionBuffer) {
            this.traditionalExhaustionBuffer = traditionalExhaustionBuffer;
            return this;
        }

        public Builder rmdBracketHeadroom(BigDecimal rmdBracketHeadroom) {
            this.rmdBracketHeadroom = rmdBracketHeadroom;
            return this;
        }

        public Builder dynamicSequencingBracketRate(BigDecimal dynamicSequencingBracketRate) {
            this.dynamicSequencingBracketRate = dynamicSequencingBracketRate;
            return this;
        }

        public Builder dividendYield(BigDecimal dividendYield) {
            this.dividendYield = dividendYield;
            return this;
        }

        public Builder feeRate(BigDecimal feeRate) {
            this.feeRate = feeRate;
            return this;
        }

        public Builder baseYear(int baseYear) {
            this.baseYear = baseYear;
            return this;
        }

        public Builder includeDepressionYears(boolean includeDepressionYears) {
            this.includeDepressionYears = includeDepressionYears;
            return this;
        }

        public Builder interestYield(BigDecimal interestYield) {
            this.interestYield = interestYield;
            return this;
        }

        public Builder gateOnAdaptiveRules(boolean gateOnAdaptiveRules) {
            this.gateOnAdaptiveRules = gateOnAdaptiveRules;
            return this;
        }

        public Builder spouseBirthYear(@Nullable Integer spouseBirthYear) {
            this.spouseBirthYear = spouseBirthYear;
            return this;
        }

        public Builder primaryDeathAge(@Nullable Integer primaryDeathAge) {
            this.primaryDeathAge = primaryDeathAge;
            return this;
        }

        public Builder spouseDeathAge(@Nullable Integer spouseDeathAge) {
            this.spouseDeathAge = spouseDeathAge;
            return this;
        }

        public Builder survivorSpendingFactor(@Nullable BigDecimal survivorSpendingFactor) {
            this.survivorSpendingFactor = survivorSpendingFactor;
            return this;
        }

        public Builder communityProperty(boolean communityProperty) {
            this.communityProperty = communityProperty;
            return this;
        }

        public Builder stochasticMortality(@Nullable Boolean stochasticMortality) {
            this.stochasticMortality = stochasticMortality;
            return this;
        }

        public Builder primarySex(@Nullable String primarySex) {
            this.primarySex = primarySex;
            return this;
        }

        public Builder spouseSex(@Nullable String spouseSex) {
            this.spouseSex = spouseSex;
            return this;
        }

        public Builder longevityConditionalAge(@Nullable Integer longevityConditionalAge) {
            this.longevityConditionalAge = longevityConditionalAge;
            return this;
        }

        public Builder mortalityTable(@Nullable MortalityTable mortalityTable) {
            this.mortalityTable = mortalityTable;
            return this;
        }

        public GuardrailOptimizationInput build() {
            return new GuardrailOptimizationInput(retirementDate, birthYear, endAge, inflationRate, accounts,
                    incomeSources, essentialFloor, terminalBalanceTarget, returnMean, trialCount, confidenceLevel,
                    phases, seed, portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears, cashReserveYears,
                    cashReturnRate, filingStatus, withdrawalOrder, optimizeConversions, conversionBracketRate,
                    rmdTargetBracketRate, traditionalExhaustionBuffer, rmdBracketHeadroom,
                    dynamicSequencingBracketRate, dividendYield, feeRate,
                    baseYear != null ? baseYear : retirementDate.getYear(), includeDepressionYears, interestYield,
                    gateOnAdaptiveRules, spouseBirthYear, primaryDeathAge, spouseDeathAge, survivorSpendingFactor,
                    communityProperty, stochasticMortality, primarySex, spouseSex, longevityConditionalAge,
                    mortalityTable);
        }
    }
}
