package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record GuardrailPhaseInput(
        String name,
        @Min(0) @Max(120) int startAge,
        @Min(0) @Max(120) Integer endAge,
        @Min(0) int priorityWeight,
        @DecimalMin("0") BigDecimal targetSpending
) {

    public GuardrailPhaseInput(String name, int startAge, Integer endAge, int priorityWeight) {
        this(name, startAge, endAge, priorityWeight, null);
    }

    /** A phase that ends before it starts covers no ages. */
    @JsonIgnore
    @AssertTrue(message = "end_age must not be before start_age")
    public boolean isEndAgeNotBeforeStartAge() {
        return endAge == null || endAge >= startAge;
    }
}
