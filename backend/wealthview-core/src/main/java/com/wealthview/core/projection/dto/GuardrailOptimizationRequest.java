package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * {@code trialCount} and {@code confidenceLevel} are bounded (T18a-5c): too few trials make the
 * success-rate estimate too noisy to trust, too many burn CPU for no statistical gain; a
 * confidence level outside (0.5, 1.0) is not a meaningful target (below 0.5 accepts a coin-flip
 * outcome, at/above 1.0 is unachievable in a finite Monte Carlo sample). Both fields are
 * {@code Integer}/{@code BigDecimal} (nullable) elsewhere in the request lifecycle, so the bounds
 * only fire when a value is actually supplied -- {@code @Min}/{@code @Max}/{@code @DecimalMin}/
 * {@code @DecimalMax} are no-ops on {@code null} by Jakarta Bean Validation convention.
 */
public record GuardrailOptimizationRequest(
        UUID scenarioId,
        String name,
        BigDecimal essentialFloor,
        BigDecimal terminalBalanceTarget,
        BigDecimal returnMean,
        @Min(100) @Max(50000) Integer trialCount,
        @DecimalMin("0.5") @DecimalMax("0.999") BigDecimal confidenceLevel,
        List<GuardrailPhaseInput> phases,
        BigDecimal portfolioFloor,
        BigDecimal maxAnnualAdjustmentRate,
        Integer phaseBlendYears,
        String riskTolerance,
        Integer cashReserveYears,
        BigDecimal cashReturnRate,
        Boolean optimizeConversions,
        BigDecimal conversionBracketRate,
        BigDecimal rmdTargetBracketRate,
        Integer traditionalExhaustionBuffer,
        BigDecimal rmdBracketHeadroom,
        BigDecimal dynamicSequencingBracketRate
) {}
