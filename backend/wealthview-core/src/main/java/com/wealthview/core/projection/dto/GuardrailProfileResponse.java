package com.wealthview.core.projection.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;

import java.math.BigDecimal;
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
        BigDecimal returnStddev,
        int trialCount,
        BigDecimal confidenceLevel,
        List<GuardrailPhaseInput> phases,
        List<GuardrailYearlySpending> yearlySpending,
        BigDecimal medianFinalBalance,
        BigDecimal failureRate,
        BigDecimal percentile10Final,
        BigDecimal percentile90Final,
        boolean stale,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        return new GuardrailProfileResponse(
                entity.getId(),
                entity.getScenario().getId(),
                entity.getName(),
                entity.getEssentialFloor(),
                entity.getTerminalBalanceTarget(),
                entity.getReturnMean(),
                entity.getReturnStddev(),
                entity.getTrialCount(),
                entity.getConfidenceLevel(),
                phases,
                yearlySpending,
                entity.getMedianFinalBalance(),
                entity.getFailureRate(),
                entity.getPercentile10Final(),
                entity.getPercentile90Final(),
                entity.isStale(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
