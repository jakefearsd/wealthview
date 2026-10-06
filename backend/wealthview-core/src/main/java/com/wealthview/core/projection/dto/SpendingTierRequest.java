package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record SpendingTierRequest(
        String name,
        @Min(0) @Max(120) int startAge,
        @Min(0) @Max(120) Integer endAge,
        @DecimalMin("0") BigDecimal essentialExpenses,
        @DecimalMin("0") BigDecimal discretionaryExpenses) {

    /** A tier that ends before it starts matches no age. */
    @JsonIgnore
    @AssertTrue(message = "end_age must not be before start_age")
    public boolean isEndAgeNotBeforeStartAge() {
        return endAge == null || endAge >= startAge;
    }
}
