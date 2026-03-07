package com.wealthview.core.property.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PropertyExpenseRequest(
        @NotNull LocalDate date,
        @NotNull BigDecimal amount,
        @NotNull @Pattern(regexp = "mortgage|tax|insurance|maintenance|capex|hoa|mgmt_fee") String category,
        String description,
        @Pattern(regexp = "monthly|annual") String frequency
) {
}
