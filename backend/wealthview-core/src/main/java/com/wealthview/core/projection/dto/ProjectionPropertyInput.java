package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProjectionPropertyInput(
        UUID propertyId,
        String name,
        BigDecimal currentValue,
        BigDecimal mortgageBalance,
        BigDecimal annualAppreciationRate,
        BigDecimal loanAmount,
        BigDecimal annualInterestRate,
        int loanTermMonths,
        LocalDate loanStartDate
) {}
