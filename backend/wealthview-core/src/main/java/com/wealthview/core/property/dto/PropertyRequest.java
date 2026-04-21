package com.wealthview.core.property.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PropertyRequest(
        @NotBlank String address,
        @NotNull @DecimalMin("0") BigDecimal purchasePrice,
        @NotNull LocalDate purchaseDate,
        @NotNull @DecimalMin("0") BigDecimal currentValue,
        @DecimalMin("0") BigDecimal mortgageBalance,
        @DecimalMin("0") BigDecimal loanAmount,
        BigDecimal annualInterestRate,
        Integer loanTermMonths,
        LocalDate loanStartDate,
        Boolean useComputedBalance,
        String propertyType,
        BigDecimal annualAppreciationRate,
        BigDecimal annualPropertyTax,
        BigDecimal annualInsuranceCost,
        BigDecimal annualMaintenanceCost,
        LocalDate inServiceDate,
        BigDecimal landValue,
        String depreciationMethod,
        BigDecimal usefulLifeYears,
        List<CostSegAllocation> costSegAllocations,
        BigDecimal bonusDepreciationRate,
        Integer costSegStudyYear
) {
}
