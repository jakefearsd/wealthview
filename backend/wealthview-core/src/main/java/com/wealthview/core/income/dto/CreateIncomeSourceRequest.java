package com.wealthview.core.income.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateIncomeSourceRequest(
        @NotBlank String name,
        @NotBlank String incomeType,
        @NotNull @DecimalMin("0") BigDecimal annualAmount,
        @Min(0) int startAge,
        @Min(0) Integer endAge,
        @DecimalMin("0") @DecimalMax("1") BigDecimal inflationRate,
        Boolean oneTime,
        String taxTreatment,
        UUID propertyId,
        // Household/survivor modeling (sub-project A): owner in {primary, spouse}, null -> primary.
        String owner,
        // Non-SS survivor continuation fraction (0-1, ignored for SS-typed sources); null -> 1.0.
        @DecimalMin("0") @DecimalMax("1") BigDecimal survivorPercent
) {}
