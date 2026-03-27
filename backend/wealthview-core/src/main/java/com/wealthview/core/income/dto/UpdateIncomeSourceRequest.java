package com.wealthview.core.income.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateIncomeSourceRequest(
        @NotBlank String name,
        @NotBlank String incomeType,
        @NotNull @DecimalMin("0") BigDecimal annualAmount,
        @Min(0) int startAge,
        @Min(0) Integer endAge,
        @DecimalMin("0") @DecimalMax("1") BigDecimal inflationRate,
        Boolean oneTime,
        String taxTreatment,
        UUID propertyId
) {}
