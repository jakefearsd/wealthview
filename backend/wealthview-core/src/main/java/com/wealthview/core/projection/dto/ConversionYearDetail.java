package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record ConversionYearDetail(
        int calendarYear,
        int age,
        BigDecimal conversionAmount,
        BigDecimal estimatedTax,
        BigDecimal traditionalBalanceAfter,
        BigDecimal rothBalanceAfter,
        BigDecimal projectedRmd,
        BigDecimal otherIncome,
        BigDecimal totalTaxableIncome,
        String bracketUsed
) {}
