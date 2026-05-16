package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PropertyIncomeRequest(
        @NotNull LocalDate date,
        @NotNull BigDecimal amount,
        @NotNull @Pattern(regexp = "rent|other") String category,
        String description,
        @Pattern(regexp = "monthly|annual") String frequency
) {
}
