package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.util.List;

public record PropertyAnalyticsResponse(
        String propertyType,
        BigDecimal totalAppreciation,
        BigDecimal appreciationPercent,
        MortgageProgress mortgageProgress,
        List<EquityGrowthPoint> equityGrowth,
        BigDecimal capRate,
        BigDecimal annualNoi,
        BigDecimal cashOnCashReturn,
        BigDecimal annualNetCashFlow,
        BigDecimal totalCashInvested
) {
}
