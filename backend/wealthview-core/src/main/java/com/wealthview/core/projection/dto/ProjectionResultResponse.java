package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProjectionResultResponse(
        UUID scenarioId,
        List<ProjectionYearDto> yearlyData,
        BigDecimal finalBalance,
        int yearsInRetirement,
        SpendingFeasibilitySummary spendingFeasibility) {
}
