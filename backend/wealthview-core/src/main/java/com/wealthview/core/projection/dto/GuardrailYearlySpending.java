package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record GuardrailYearlySpending(
        int year,
        int age,
        BigDecimal recommended,
        BigDecimal corridorLow,
        BigDecimal corridorHigh,
        BigDecimal essentialFloor,
        BigDecimal discretionary,
        BigDecimal incomeOffset,
        BigDecimal portfolioWithdrawal,
        String phaseName
) {}
