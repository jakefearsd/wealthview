package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RentalPropertyYearDetail(
        UUID incomeSourceId,
        String propertyName,
        String taxTreatment,
        BigDecimal grossRent,
        BigDecimal operatingExpenses,
        BigDecimal mortgageInterest,
        BigDecimal propertyTax,
        BigDecimal depreciation,
        BigDecimal netTaxableIncome,
        BigDecimal lossAppliedToIncome,
        BigDecimal lossSuspended,
        BigDecimal suspendedLossCarryforward,
        BigDecimal cashFlow
) {}
