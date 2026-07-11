package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
        BigDecimal fixedReturnShare
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity) {
        return from(entity, null);
    }

    /**
     * Same as {@link #from(GuardrailSpendingProfileEntity)}, but also attaches a freshly
     * computed {@code fixedReturnShare} (only available right after {@code optimize()} runs,
     * where live account inputs are on hand — {@link #from(GuardrailSpendingProfileEntity)}
     * alone always reports {@code null} since a persisted profile does not store this value).
     */
    @SuppressWarnings("PMD.UseDiamondOperator")
    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity, BigDecimal fixedReturnShare) {
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
                fixedReturnShare
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
