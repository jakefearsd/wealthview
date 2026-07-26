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
        BigDecimal dynamicSequencingBracketRate,
        // T24: per-profile toggle for the sustainability search gate; see #gateOnAdaptiveRules().
        Boolean gateOnAdaptiveRules
) {

    /**
     * Back-compat convenience for callers that predate the T24 {@link #gateOnAdaptiveRules} toggle.
     * Defaults it to {@code null} — "not specified". Since V077 (default-to-on, user decision),
     * {@code GuardrailProfileService.buildOptimizationInput} resolves an unspecified toggle to
     * {@code true} (gate on the with-rules metric); an EXPLICIT {@code false} is fully honored and
     * remains the conservative, byte-identical-to-pre-T24 no-adaptation-gate anchor.
     */
    // ExcessiveParameterList: mirrors the record's own 21-field canonical constructor (pre-T24
    // shape) so existing positional call sites keep compiling unchanged.
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GuardrailOptimizationRequest(
            UUID scenarioId, String name, BigDecimal essentialFloor, BigDecimal terminalBalanceTarget,
            BigDecimal returnMean, Integer trialCount, BigDecimal confidenceLevel,
            List<GuardrailPhaseInput> phases, BigDecimal portfolioFloor, BigDecimal maxAnnualAdjustmentRate,
            Integer phaseBlendYears, String riskTolerance, Integer cashReserveYears, BigDecimal cashReturnRate,
            Boolean optimizeConversions, BigDecimal conversionBracketRate, BigDecimal rmdTargetBracketRate,
            Integer traditionalExhaustionBuffer, BigDecimal rmdBracketHeadroom,
            BigDecimal dynamicSequencingBracketRate) {
        this(scenarioId, name, essentialFloor, terminalBalanceTarget, returnMean, trialCount, confidenceLevel,
                phases, portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears, riskTolerance, cashReserveYears,
                cashReturnRate, optimizeConversions, conversionBracketRate, rmdTargetBracketRate,
                traditionalExhaustionBuffer, rmdBracketHeadroom, dynamicSequencingBracketRate, null);
    }

    /**
     * Entry point for naming components instead of counting positions. Every component is nullable
     * ("not specified" — the service layer resolves the defaults), so nothing is required up front
     * and an unset field arrives at the canonical constructor as {@code null}, exactly as an omitted
     * JSON property does.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder over the canonical 21-component constructor. One setter per component, in
     * component order, so a reader can diff a call chain against the record header line by line.
     */
    // TooManyFields: one field per record component is the nature of a builder for a 21-component
    // record; splitting it would defeat the purpose.
    @SuppressWarnings("PMD.TooManyFields")
    public static final class Builder {
        private UUID scenarioId;
        private String name;
        private BigDecimal essentialFloor;
        private BigDecimal terminalBalanceTarget;
        private BigDecimal returnMean;
        private Integer trialCount;
        private BigDecimal confidenceLevel;
        private List<GuardrailPhaseInput> phases;
        private BigDecimal portfolioFloor;
        private BigDecimal maxAnnualAdjustmentRate;
        private Integer phaseBlendYears;
        private String riskTolerance;
        private Integer cashReserveYears;
        private BigDecimal cashReturnRate;
        private Boolean optimizeConversions;
        private BigDecimal conversionBracketRate;
        private BigDecimal rmdTargetBracketRate;
        private Integer traditionalExhaustionBuffer;
        private BigDecimal rmdBracketHeadroom;
        private BigDecimal dynamicSequencingBracketRate;
        private Boolean gateOnAdaptiveRules;

        private Builder() {}

        public Builder scenarioId(UUID scenarioId) {
            this.scenarioId = scenarioId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
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

        public Builder trialCount(Integer trialCount) {
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

        public Builder portfolioFloor(BigDecimal portfolioFloor) {
            this.portfolioFloor = portfolioFloor;
            return this;
        }

        public Builder maxAnnualAdjustmentRate(BigDecimal maxAnnualAdjustmentRate) {
            this.maxAnnualAdjustmentRate = maxAnnualAdjustmentRate;
            return this;
        }

        public Builder phaseBlendYears(Integer phaseBlendYears) {
            this.phaseBlendYears = phaseBlendYears;
            return this;
        }

        public Builder riskTolerance(String riskTolerance) {
            this.riskTolerance = riskTolerance;
            return this;
        }

        public Builder cashReserveYears(Integer cashReserveYears) {
            this.cashReserveYears = cashReserveYears;
            return this;
        }

        public Builder cashReturnRate(BigDecimal cashReturnRate) {
            this.cashReturnRate = cashReturnRate;
            return this;
        }

        public Builder optimizeConversions(Boolean optimizeConversions) {
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

        public Builder traditionalExhaustionBuffer(Integer traditionalExhaustionBuffer) {
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

        public Builder gateOnAdaptiveRules(Boolean gateOnAdaptiveRules) {
            this.gateOnAdaptiveRules = gateOnAdaptiveRules;
            return this;
        }

        public GuardrailOptimizationRequest build() {
            return new GuardrailOptimizationRequest(scenarioId, name, essentialFloor, terminalBalanceTarget,
                    returnMean, trialCount, confidenceLevel, phases, portfolioFloor, maxAnnualAdjustmentRate,
                    phaseBlendYears, riskTolerance, cashReserveYears, cashReturnRate, optimizeConversions,
                    conversionBracketRate, rmdTargetBracketRate, traditionalExhaustionBuffer, rmdBracketHeadroom,
                    dynamicSequencingBracketRate, gateOnAdaptiveRules);
        }
    }
}
