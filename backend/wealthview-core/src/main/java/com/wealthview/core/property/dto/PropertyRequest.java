package com.wealthview.core.property.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PropertyRequest(
        @NotBlank String address,
        @NotNull BigDecimal purchasePrice,
        @NotNull LocalDate purchaseDate,
        @NotNull BigDecimal currentValue,
        BigDecimal mortgageBalance,
        BigDecimal loanAmount,
        BigDecimal annualInterestRate,
        Integer loanTermMonths,
        LocalDate loanStartDate,
        Boolean useComputedBalance,
        String propertyType
) {
}
