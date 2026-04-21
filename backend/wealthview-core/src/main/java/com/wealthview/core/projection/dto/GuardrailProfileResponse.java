package com.wealthview.core.projection.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
        RothConversionScheduleResponse conversionSchedule
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GuardrailProfileResponse(UUID id, UUID scenarioId, String name,
                                     BigDecimal essentialFloor, BigDecimal terminalBalanceTarget,
                                     BigDecimal returnMean,
                                     int trialCount, BigDecimal confidenceLevel,
                                     List<GuardrailPhaseInput> phases,
                                     List<GuardrailYearlySpending> yearlySpending,
                                     BigDecimal medianFinalBalance, BigDecimal failureRate,
                                     BigDecimal percentile10Final,
                                     boolean stale, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, scenarioId, name, essentialFloor, terminalBalanceTarget,
                returnMean, trialCount, confidenceLevel,
                phases, yearlySpending, medianFinalBalance, failureRate,
                percentile10Final, stale, createdAt, updatedAt,
                BigDecimal.ZERO, null, 0, null,
                2, new BigDecimal("0.04"), null);
    }

    public static GuardrailProfileResponse from(GuardrailSpendingProfileEntity entity) {
        List<GuardrailPhaseInput> phases;
        List<GuardrailYearlySpending> yearlySpending;
        try {
            phases = MAPPER.readValue(entity.getPhases(),
                    new TypeReference<List<GuardrailPhaseInput>>() {});
            yearlySpending = MAPPER.readValue(entity.getYearlySpending(),
                    new TypeReference<List<GuardrailYearlySpending>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            phases = List.of();
            yearlySpending = List.of();
        }

        RothConversionScheduleResponse conversionSchedule = null;
        if (entity.getConversionSchedule() != null && !entity.getConversionSchedule().isBlank()) {
            try {
                conversionSchedule = MAPPER.readValue(entity.getConversionSchedule(),
                        RothConversionScheduleResponse.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
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
                conversionSchedule
        );
    }

    static boolean isOlderThan24Hours(GuardrailSpendingProfileEntity entity) {
        var updated = entity.getUpdatedAt();
        return updated != null && Duration.between(updated, OffsetDateTime.now()).toHours() >= 24;
    }
}
