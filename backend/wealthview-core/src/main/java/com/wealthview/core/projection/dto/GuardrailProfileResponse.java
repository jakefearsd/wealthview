package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Guardrail/MC optimization result returned to the API layer and persisted on the profile entity.
 *
 * @param returnMean the RESOLVED effective growth rate the Roth-conversion simulator actually used
 *         — a REAL, fee-adjusted rate (audit C4; see
 *         {@code OptimizationContextBuilder.resolveReturnMean}), NOT the request's nominal
 *         {@code return_mean}. Display-only wire field ({@code return_mean}); deliberately echoed
 *         in real terms so users see the rate that actually drove the schedule. Because the stored
 *         value is real, it is never fed back into a request (whose contract is nominal) — see
 *         {@code GuardrailProfileService.reoptimize}.
 * @param disclosure T23 item 4: the computed-only disclosure fields (audits C4/C6/C9/T24) grouped
 *         into one nested, {@link JsonUnwrapped @JsonUnwrapped} record so the wire shape stays flat
 *         (byte-identical snake_case keys) while collapsing what had grown into four positional
 *         back-compat constructors. Despite the shared "not computed for this response" theme, the
 *         five fields are NOT strictly always-null-together: {@link Disclosure#floorReduced} is a
 *         primitive {@code boolean} (never null, defaults {@code false}) and {@link
 *         Disclosure#gatedOn} is never null either (defaults {@link #GATED_ON_NO_ADAPTATION}) — only
 *         {@link Disclosure#fixedReturnShare}, {@link Disclosure#originalFloorSuccessProbability},
 *         and {@link Disclosure#successProbabilityWithRules} are nullable. See {@link Disclosure}'s
 *         own javadoc for each field's precise semantics.
 * @param stochasticMortality Sub-project B (stochastic mortality), task 8: the optional
 *         longevity-aware summary (task 7's {@code StochasticMortalitySummary}, mapped by {@code
 *         GuardrailResponseBuilder}) -- present only when the run opted into the stochastic-mortality
 *         toggle. Unlike {@link #disclosure}, this is a plain nested field (NOT {@code
 *         @JsonUnwrapped}): it serializes as its own {@code stochastic_mortality} object rather than
 *         flattening into top-level keys. {@code null} for every toggle-off run, degenerate
 *         zero-year run, and persisted-profile read (not a persisted column) -- the byte-identical
 *         pre-task-8 shape.
 */
public record GuardrailProfileResponse(
        UUID id,
        UUID scenarioId,
        String name,
        BigDecimal essentialFloor,
        BigDecimal terminalBalanceTarget,
        BigDecimal returnMean,
        int trialCount,
        BigDecimal confidenceLevel,
        List<GuardrailPhaseInput> phases,
        List<GuardrailYearlySpending> yearlySpending,
        BigDecimal medianFinalBalance,
        BigDecimal failureRate,
        BigDecimal successProbability,
        BigDecimal percentile10Final,
        boolean stale,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        BigDecimal portfolioFloor,
        BigDecimal maxAnnualAdjustmentRate,
        int phaseBlendYears,
        String riskTolerance,
        int cashReserveYears,
        BigDecimal cashReturnRate,
        RothConversionScheduleResponse conversionSchedule,
        @JsonUnwrapped Disclosure disclosure,
        StochasticMortalityResponse stochasticMortality
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@link Disclosure#gatedOn} value when the search gated on the original no-adaptation rate. */
    public static final String GATED_ON_NO_ADAPTATION = "no_adaptation";
    /** {@link Disclosure#gatedOn} value when the search gated on the audit-C9 with-rules rate. */
    public static final String GATED_ON_WITH_RULES = "with_rules";

    /**
     * Never-null-group invariant (mirrors {@code ProjectionYearDto}'s own {@code @JsonUnwrapped}
     * groups): a {@code null} {@link #disclosure} would make Jackson omit all five of its keys,
     * silently breaking the flat wire contract. Every construction path gets a non-null group with
     * the "nothing computed for this response" defaults instead.
     */
    public GuardrailProfileResponse {
        disclosure = disclosure != null ? disclosure : Disclosure.empty();
    }

    /**
     * The computed-only disclosure fields grouped for {@code @JsonUnwrapped} flattening -- see the
     * canonical record's {@code disclosure} javadoc for why they are grouped despite not sharing
     * one nullability convention.
     *
     * @param fixedReturnShare A3: legacy fixed-return-override MC dispersion warning. {@code null}
     *         — "not computed for this response" — for every response that predates it (persisted-
     *         profile reads via {@link GuardrailProfileResponse#from(GuardrailSpendingProfileEntity)}
     *         report {@code null}; it is not a persisted column).
     * @param floorReduced audit C6: {@code true} when {@code SustainabilitySearch.verifyEssentialFloor}
     *         clamped the user's essential floor down to portfolio capacity in at least one
     *         projection year — the headline {@code successProbability} then measures the REDUCED
     *         floor, not the floor the user actually asked for. {@code false} (never {@code null})
     *         when no clamp occurred, or when this response predates the disclosure.
     * @param originalFloorSuccessProbability audit C6: the success rate measured against the user's
     *         UNCLAMPED essential floor (one extra simulation pass, same trials/discretionary plan
     *         as the headline run), computed only when {@link #floorReduced} is {@code true}.
     *         {@code null} when no clamp occurred — the headline {@code successProbability} already
     *         reflects the user's floor in that case — or when this response predates the disclosure.
     * @param successProbabilityWithRules audit C9: the essential-floor success rate when the
     *         SIMULATED guardrail adaptation rule is active — the SAME trials/paths as
     *         {@code successProbability} re-run with the in-simulation spending rule switched on.
     *         The rule's trigger is a portfolio-ratio PROXY (planned spending scaled by the trial's
     *         portfolio relative to the no-adaptation median) GATED BY the displayed corridor
     *         thresholds — it is not derived the way the corridor itself was; when the proxy
     *         breaches the lower band, discretionary is cut toward the proxy, so simulated spending
     *         CAN fall below {@code corridor_low} (floor-bounded); recovery is toward, never above,
     *         the planned schedule. This field therefore measures "success when following this
     *         specific ratio-cut rule", not "spending always kept inside the shown corridor".
     *         Reporting-only this pass: the optimizer's gate/objective still use the no-adaptation
     *         {@code successProbability} by default, unless T24's {@code gate_on_adaptive_rules}
     *         toggle is on. Expected {@code >=} the no-adaptation rate: floors are never cut (no
     *         single-year self-inflicted shortfall is possible) and with-rules spending never
     *         exceeds the plan; across cumulative multi-year tax paths this monotonicity is
     *         empirically pinned (integration tests incl. RMD/tax dynamics; a 60,000-trial-pair
     *         adversarial probe found zero regressions), not formally proven. {@code null} when no
     *         guardrail adaptation applies (no positive {@code maxAnnualAdjustmentRate}, degenerate
     *         zero-year runs) or when the response predates the disclosure (persisted-profile
     *         reads — it is a computed-only field, not persisted).
     * @param gatedOn T24: which success metric actually certified the recommended schedule --
     *         {@link GuardrailProfileResponse#GATED_ON_WITH_RULES} when the profile's
     *         {@code gate_on_adaptive_rules} toggle was on AND a positive
     *         {@code maxAnnualAdjustmentRate} made the rule effective (the sustainability search
     *         gated candidates on {@link #successProbabilityWithRules}), otherwise
     *         {@link GuardrailProfileResponse#GATED_ON_NO_ADAPTATION} (the search gated on the
     *         no-adaptation rate, the original and still-default behavior). Derived by
     *         {@link GuardrailProfileResponse#resolveGatedOn}, the single shared rule so a fresh
     *         optimize response and a persisted-profile read always agree. Never {@code null}.
     */
    public record Disclosure(
            BigDecimal fixedReturnShare,
            boolean floorReduced,
            BigDecimal originalFloorSuccessProbability,
            BigDecimal successProbabilityWithRules,
            String gatedOn) {

        /** "Nothing computed for this response" -- the pre-disclosure/predates-the-field shape. */
        public static Disclosure empty() {
            return new Disclosure(null, false, null, null, GATED_ON_NO_ADAPTATION);
        }
    }

    /**
     * Back-compat convenience for callers that predate ALL FIVE {@link Disclosure} fields (T23
     * item 4 collapsed the pre-existing 24/26/27/28-field constructor chain to this single
     * overload, since it already covered the large majority of positional call sites). Defaults
     * {@link #disclosure} to {@link Disclosure#empty()} — "not computed for this response" — so
     * existing positional call sites (optimizer builders, tests) that don't have any disclosure
     * data on hand keep compiling unchanged.
     */
    // ExcessiveParameterList: this constructor deliberately mirrors the record's own 24-field
    // canonical constructor (pre-disclosure shape) so existing positional call sites keep
    // compiling unchanged; it isn't new incidental complexity, just a back-compat delegate.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GuardrailProfileResponse(
            UUID id,
            UUID scenarioId,
            String name,
            BigDecimal essentialFloor,
            BigDecimal terminalBalanceTarget,
            BigDecimal returnMean,
            int trialCount,
            BigDecimal confidenceLevel,
            List<GuardrailPhaseInput> phases,
            List<GuardrailYearlySpending> yearlySpending,
            BigDecimal medianFinalBalance,
            BigDecimal failureRate,
            BigDecimal successProbability,
            BigDecimal percentile10Final,
            boolean stale,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            BigDecimal portfolioFloor,
            BigDecimal maxAnnualAdjustmentRate,
            int phaseBlendYears,
            String riskTolerance,
            int cashReserveYears,
            BigDecimal cashReturnRate,
            RothConversionScheduleResponse conversionSchedule) {
        this(id, scenarioId, name, essentialFloor, terminalBalanceTarget, returnMean, trialCount, confidenceLevel,
                phases, yearlySpending, medianFinalBalance, failureRate, successProbability, percentile10Final,
                stale, createdAt, updatedAt, portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears,
                riskTolerance, cashReserveYears, cashReturnRate, conversionSchedule, Disclosure.empty());
    }

    /**
     * Back-compat convenience for callers that predate {@link #stochasticMortality} (sub-project B,
     * task 8 -- added on top of T23 item 4's {@link Disclosure} grouping): defaults it to {@code
     * null}, "no stochastic-mortality evaluation for this response", the byte-identical
     * pre-task-8 shape. Existing positional call sites that already pass a {@link Disclosure} but
     * have no summary on hand (persisted-profile reads, the degenerate zero-year {@code
     * emptyResult}, and pre-task-8 tests) keep compiling unchanged.
     */
    // ExcessiveParameterList: mirrors the record's own canonical constructor (pre-task-8 shape) so
    // existing positional call sites keep compiling unchanged; not new incidental complexity.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GuardrailProfileResponse(
            UUID id,
            UUID scenarioId,
            String name,
            BigDecimal essentialFloor,
            BigDecimal terminalBalanceTarget,
            BigDecimal returnMean,
            int trialCount,
            BigDecimal confidenceLevel,
            List<GuardrailPhaseInput> phases,
            List<GuardrailYearlySpending> yearlySpending,
            BigDecimal medianFinalBalance,
            BigDecimal failureRate,
            BigDecimal successProbability,
            BigDecimal percentile10Final,
            boolean stale,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            BigDecimal portfolioFloor,
            BigDecimal maxAnnualAdjustmentRate,
            int phaseBlendYears,
            String riskTolerance,
            int cashReserveYears,
            BigDecimal cashReturnRate,
            RothConversionScheduleResponse conversionSchedule,
            Disclosure disclosure) {
        this(id, scenarioId, name, essentialFloor, terminalBalanceTarget, returnMean, trialCount, confidenceLevel,
                phases, yearlySpending, medianFinalBalance, failureRate, successProbability, percentile10Final,
                stale, createdAt, updatedAt, portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears,
                riskTolerance, cashReserveYears, cashReturnRate, conversionSchedule, disclosure, null);
    }

    // --- Flat delegate accessors (mirrors ProjectionYearDto's grouped-field pattern): preserve
    // every existing `.fixedReturnShare()`/`.floorReduced()`/etc. call site across the codebase
    // without callers needing to know about the Disclosure grouping. ---

    public BigDecimal fixedReturnShare() {
        return disclosure.fixedReturnShare();
    }

    public boolean floorReduced() {
        return disclosure.floorReduced();
    }

    public BigDecimal originalFloorSuccessProbability() {
        return disclosure.originalFloorSuccessProbability();
    }

    public BigDecimal successProbabilityWithRules() {
        return disclosure.successProbabilityWithRules();
    }

    public String gatedOn() {
        return disclosure.gatedOn();
    }

    /**
     * T24: derives {@link Disclosure#gatedOn} from the two inputs that determine whether the
     * sustainability search's in-simulation adaptation rule was actually effective during the
     * search -- the SAME rule {@code SustainabilitySearch.isSustainable} applies. Shared by a
     * fresh optimize response ({@code GuardrailResponseBuilder}/{@code MonteCarloSpendingOptimizer},
     * from the live {@code GuardrailOptimizationInput}) and every persisted-profile read ({@link
     * #from(GuardrailSpendingProfileEntity, BigDecimal, boolean, BigDecimal, BigDecimal)}, from the
     * two persisted entity columns) so both always agree.
     */
    public static String resolveGatedOn(boolean gateOnAdaptiveRules, BigDecimal maxAnnualAdjustmentRate) {
        boolean withRules = gateOnAdaptiveRules
                && maxAnnualAdjustmentRate != null
                && maxAnnualAdjustmentRate.signum() > 0;
        return withRules ? GATED_ON_WITH_RULES : GATED_ON_NO_ADAPTATION;
    }

    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity) {
        return from(entity, null);
    }

    /**
     * Same as {@link #from(GuardrailSpendingProfileEntity)}, but also attaches a freshly
     * computed {@code fixedReturnShare} (only available right after {@code optimize()} runs,
     * where live account inputs are on hand — {@link #from(GuardrailSpendingProfileEntity)}
     * alone always reports {@code null} since a persisted profile does not store this value).
     */
    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity, BigDecimal fixedReturnShare) {
        return from(entity, fixedReturnShare, false, null);
    }

    /**
     * Same as {@link #from(GuardrailSpendingProfileEntity, BigDecimal)}, but also attaches the
     * freshly computed floor-clamp disclosure (audit C6: only available right after {@code
     * optimize()} runs, where the trial simulations are on hand — neither field is persisted, so
     * every other overload reports the "no clamp info" defaults {@code false} / {@code null}).
     */
    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity, BigDecimal fixedReturnShare,
                                                boolean floorReduced, BigDecimal originalFloorSuccessProbability) {
        return from(entity, fixedReturnShare, floorReduced, originalFloorSuccessProbability, null);
    }

    /**
     * Same as {@link #from(GuardrailSpendingProfileEntity, BigDecimal, boolean, BigDecimal)}, but
     * also attaches the freshly computed with-rules success rate (audit C9: only available right
     * after {@code optimize()} runs — it is a computed-only field, not persisted, so every other
     * overload reports {@code null}).
     */
    @SuppressWarnings("PMD.UseDiamondOperator")
    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity, BigDecimal fixedReturnShare,
                                                boolean floorReduced, BigDecimal originalFloorSuccessProbability,
                                                BigDecimal successProbabilityWithRules) {
        List<GuardrailPhaseInput> phases;
        List<GuardrailYearlySpending> yearlySpending;
        try {
            phases = MAPPER.readValue(entity.getPhases(),
                    new TypeReference<List<GuardrailPhaseInput>>() {});
            yearlySpending = MAPPER.readValue(entity.getYearlySpending(),
                    new TypeReference<List<GuardrailYearlySpending>>() {});
        } catch (tools.jackson.core.JacksonException e) {
            phases = List.of();
            yearlySpending = List.of();
        }

        RothConversionScheduleResponse conversionSchedule = null;
        if (entity.getConversionSchedule() != null && !entity.getConversionSchedule().isBlank()) {
            try {
                conversionSchedule = MAPPER.readValue(entity.getConversionSchedule(),
                        RothConversionScheduleResponse.class);
            } catch (tools.jackson.core.JacksonException ignored) {
                // leave null — entity predates conversion optimizer
            }
        }

        return new GuardrailProfileResponse(
                entity.getId(),
                entity.getScenario().getId(),
                entity.getName(),
                entity.getEssentialFloor(),
                entity.getTerminalBalanceTarget(),
                entity.getReturnMean(),
                entity.getTrialCount(),
                entity.getConfidenceLevel(),
                phases,
                yearlySpending,
                entity.getMedianFinalBalance(),
                entity.getFailureRate(),
                successProbabilityFrom(entity.getFailureRate()),
                entity.getPercentile10Final(),
                entity.isStale() || isOlderThan24Hours(entity),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPortfolioFloor(),
                entity.getMaxAnnualAdjustmentRate(),
                entity.getPhaseBlendYears(),
                entity.getRiskTolerance(),
                entity.getCashReserveYears(),
                entity.getCashReturnRate(),
                conversionSchedule,
                new Disclosure(fixedReturnShare, floorReduced, originalFloorSuccessProbability,
                        successProbabilityWithRules,
                        resolveGatedOn(entity.isGateOnAdaptiveRules(), entity.getMaxAnnualAdjustmentRate()))
        );
    }

    static boolean isOlderThan24Hours(GuardrailSpendingProfileEntity entity) {
        var updated = entity.getUpdatedAt();
        return updated != null && Duration.between(updated, OffsetDateTime.now()).toHours() >= 24;
    }

    /**
     * Persisted profiles only store {@code failureRate} (no dedicated success-probability
     * column), so the success probability is derived as its complement for wire back-compat.
     * Returns {@code null} when the profile predates result persistence.
     *
     * <p>For guardrail profiles persisted before the essential-floor success-rate optimization
     * work, {@code failureRate} holds the OLD depletion-based value (probability the portfolio
     * runs out of money), not the current essential-floor-funded definition. The derived
     * {@code successProbability} therefore reflects that old definition until the profile is
     * re-optimized — {@code GuardrailProfileService.optimize()} overwrites {@code failureRate}
     * with the essential-floor value on every re-run.
     */
    private static BigDecimal successProbabilityFrom(BigDecimal failureRate) {
        return failureRate != null ? BigDecimal.ONE.subtract(failureRate) : null;
    }
}
