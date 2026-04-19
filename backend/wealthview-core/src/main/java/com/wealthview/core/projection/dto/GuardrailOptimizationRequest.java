package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record GuardrailOptimizationRequest(
        UUID scenarioId,
        String name,
        BigDecimal essentialFloor,
        BigDecimal terminalBalanceTarget,
        BigDecimal returnMean,
        Integer trialCount,
        BigDecimal confidenceLevel,
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
