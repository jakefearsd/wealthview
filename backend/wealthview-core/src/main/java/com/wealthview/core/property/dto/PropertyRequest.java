package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PropertyRequest(
        @NotBlank String address,
        @NotNull @DecimalMin("0") BigDecimal purchasePrice,
        @NotNull LocalDate purchaseDate,
        @NotNull @DecimalMin("0") BigDecimal currentValue,
        @DecimalMin("0") BigDecimal mortgageBalance,
        @DecimalMin("0") BigDecimal loanAmount,
        @DecimalMin("0") @DecimalMax("1") BigDecimal annualInterestRate,
        @Min(1) @Max(1200) Integer loanTermMonths,
        LocalDate loanStartDate,
        Boolean useComputedBalance,
        String propertyType,
        @DecimalMin("-1") @DecimalMax("1") BigDecimal annualAppreciationRate,
        @DecimalMin("0") BigDecimal annualPropertyTax,
        @DecimalMin("0") BigDecimal annualInsuranceCost,
        @DecimalMin("0") BigDecimal annualMaintenanceCost,
        LocalDate inServiceDate,
        @DecimalMin("0") BigDecimal landValue,
        String depreciationMethod,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal usefulLifeYears,
        List<CostSegAllocation> costSegAllocations,
        @DecimalMin("0") @DecimalMax("1") BigDecimal bonusDepreciationRate,
        Integer costSegStudyYear
) {
}
