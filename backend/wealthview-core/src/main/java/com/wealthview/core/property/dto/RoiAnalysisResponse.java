package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record RoiAnalysisResponse(
        String incomeSourceName,
        BigDecimal annualRent,
        int comparisonYears,
        HoldScenarioResult hold,
        SellScenarioResult sell,
        String advantage,
        BigDecimal advantageAmount
) {
}
