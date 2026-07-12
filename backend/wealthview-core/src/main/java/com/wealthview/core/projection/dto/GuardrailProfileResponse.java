package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
 * @param floorReduced audit C6: {@code true} when {@code SustainabilitySearch.verifyEssentialFloor}
 *         clamped the user's essential floor down to portfolio capacity in at least one projection
 *         year — the headline {@link #successProbability} then measures the REDUCED floor, not the
 *         floor the user actually asked for. {@code false} (never {@code null}) when no clamp
 *         occurred, or when this response predates the disclosure (persisted-profile reads via
 *         {@link #from(GuardrailSpendingProfileEntity)}).
 * @param originalFloorSuccessProbability audit C6: the success rate measured against the user's
 *         UNCLAMPED essential floor (one extra simulation pass, same trials/discretionary plan as
 *         the headline run), computed only when {@link #floorReduced} is {@code true}. {@code null}
 *         when no clamp occurred — the headline {@link #successProbability} already reflects the
 *         user's floor in that case — or when this response predates the disclosure.
 * @param successProbabilityWithRules audit C9: the essential-floor success rate when the SIMULATED
 *         guardrail adaptation rule is active — the SAME trials/paths as {@link #successProbability}
 *         re-run with the in-simulation spending rule switched on. The rule's trigger is a
 *         portfolio-ratio PROXY (planned spending scaled by the trial's portfolio relative to the
 *         no-adaptation median) GATED BY the displayed corridor thresholds — it is not derived the
 *         way the corridor itself was; when the proxy breaches the lower band, discretionary is cut
 *         toward the proxy, so simulated spending CAN fall below {@code corridor_low}
 *         (floor-bounded); recovery is toward, never above, the planned schedule. This field
 *         therefore measures "success when following this specific ratio-cut rule", not "spending
 *         always kept inside the shown corridor". Reporting-only this pass: the optimizer's
 *         gate/objective still use the no-adaptation {@link #successProbability}, whose semantics
 *         and the sustainable-spending recommendations are unchanged. Expected {@code >=}
 *         {@link #successProbability}: floors are never cut (no single-year self-inflicted
 *         shortfall is possible) and with-rules spending never exceeds the plan; across cumulative
 *         multi-year tax paths this monotonicity is empirically pinned (integration tests incl.
 *         RMD/tax dynamics; a 60,000-trial-pair adversarial probe found zero regressions), not
 *         formally proven. {@code null} when no guardrail adaptation applies (no positive
 *         {@code maxAnnualAdjustmentRate}, degenerate zero-year runs) or when the response predates
 *         the disclosure (persisted-profile reads — it is a computed-only field, not persisted).
 *         Tracked follow-up: gate/optimize on this with-rules metric rather than reporting it only.
 * @param gatedOn T24: which success metric actually certified the recommended schedule --
 *         {@link #GATED_ON_WITH_RULES} when the profile's {@code gate_on_adaptive_rules} toggle was
 *         on AND a positive {@link #maxAnnualAdjustmentRate} made the rule effective (the
 *         sustainability search gated candidates on {@link #successProbabilityWithRules}), otherwise
 *         {@link #GATED_ON_NO_ADAPTATION} (the search gated on {@link #successProbability}, the
 *         original and still-default behavior). Derived by {@link #resolveGatedOn}, the single shared
 *         rule so a fresh optimize response and a persisted-profile read always agree.
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
        BigDecimal fixedReturnShare,
        boolean floorReduced,
        BigDecimal originalFloorSuccessProbability,
        BigDecimal successProbabilityWithRules,
        String gatedOn
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@link #gatedOn} value when the search gated on the original no-adaptation success rate. */
    public static final String GATED_ON_NO_ADAPTATION = "no_adaptation";
    /** {@link #gatedOn} value when the search gated on the audit-C9 with-rules success rate. */
    public static final String GATED_ON_WITH_RULES = "with_rules";

    /**
     * Back-compat convenience for callers that predate the {@link #fixedReturnShare} disclosure
     * field (A3: legacy fixed-return-override MC dispersion warning). Defaults it to {@code
     * null} — "not computed for this response" — rather than forcing every existing positional
     * call site (optimizer builders, tests) to learn about a field they have no account data to
     * compute.
     */
    // ExcessiveParameterList: this constructor deliberately mirrors the record's own 24-field
    // canonical constructor (pre-fixedReturnShare shape) so existing positional call sites keep
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
                riskTolerance, cashReserveYears, cashReturnRate, conversionSchedule, null);
    }

    /**
     * Back-compat convenience for callers that predate the {@link #floorReduced} /
     * {@link #originalFloorSuccessProbability} disclosure fields (audit C6: floor-clamp
     * disclosure). Defaults them to {@code false} / {@code null} — "no clamp info available for
     * this response" — so existing positional call sites (persisted-profile hydration, tests)
     * keep compiling unchanged.
     */
    // ExcessiveParameterList: mirrors the record's own 26-field canonical constructor
    // (pre-floorReduced shape) so existing positional call sites keep compiling unchanged.
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
            BigDecimal fixedReturnShare) {
        this(id, scenarioId, name, essentialFloor, terminalBalanceTarget, returnMean, trialCount, confidenceLevel,
                phases, yearlySpending, medianFinalBalance, failureRate, successProbability, percentile10Final,
                stale, createdAt, updatedAt, portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears,
                riskTolerance, cashReserveYears, cashReturnRate, conversionSchedule, fixedReturnShare,
                false, null, null);
    }

    /**
     * Back-compat convenience for callers that predate the {@link #successProbabilityWithRules}
     * disclosure field (audit C9: simulated guardrail rules). Defaults it to {@code null} — "no
     * with-rules success rate available for this response" — so existing positional call sites
     * (the audit-C6 canonical shape, tests) keep compiling unchanged.
     */
    // ExcessiveParameterList: mirrors the record's own 27-field canonical constructor
    // (pre-successProbabilityWithRules shape) so existing positional call sites keep compiling.
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
            BigDecimal fixedReturnShare,
            boolean floorReduced,
            BigDecimal originalFloorSuccessProbability) {
        this(id, scenarioId, name, essentialFloor, terminalBalanceTarget, returnMean, trialCount, confidenceLevel,
                phases, yearlySpending, medianFinalBalance, failureRate, successProbability, percentile10Final,
                stale, createdAt, updatedAt, portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears,
                riskTolerance, cashReserveYears, cashReturnRate, conversionSchedule, fixedReturnShare,
                floorReduced, originalFloorSuccessProbability, null);
    }

    /**
     * Back-compat convenience for callers that predate the T24 {@link #gatedOn} field (the pre-T24
     * 28-field canonical shape). Defaults it to {@link #GATED_ON_NO_ADAPTATION} — "the search gated
     * on the original no-adaptation metric", exactly as every caller before this toggle existed
     * behaved — so existing positional call sites (optimizer builders, tests) keep compiling and
     * behaving identically.
     */
    // ExcessiveParameterList: mirrors the record's own 28-field canonical constructor (pre-T24
    // shape) so existing positional call sites keep compiling unchanged.
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
            BigDecimal fixedReturnShare,
            boolean floorReduced,
            BigDecimal originalFloorSuccessProbability,
            BigDecimal successProbabilityWithRules) {
        this(id, scenarioId, name, essentialFloor, terminalBalanceTarget, returnMean, trialCount, confidenceLevel,
                phases, yearlySpending, medianFinalBalance, failureRate, successProbability, percentile10Final,
                stale, createdAt, updatedAt, portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears,
                riskTolerance, cashReserveYears, cashReturnRate, conversionSchedule, fixedReturnShare,
                floorReduced, originalFloorSuccessProbability, successProbabilityWithRules,
                GATED_ON_NO_ADAPTATION);
    }

    /**
     * T24: derives {@link #gatedOn} from the two inputs that determine whether the sustainability
     * search's in-simulation adaptation rule was actually effective during the search -- the SAME
     * rule {@code SustainabilitySearch.isSustainable} applies. Shared by a fresh optimize response
     * ({@code GuardrailResponseBuilder}/{@code MonteCarloSpendingOptimizer}, from the live
     * {@code GuardrailOptimizationInput}) and every persisted-profile read ({@link
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
                fixedReturnShare,
                floorReduced,
                originalFloorSuccessProbability,
                successProbabilityWithRules,
                resolveGatedOn(entity.isGateOnAdaptiveRules(), entity.getMaxAnnualAdjustmentRate())
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
