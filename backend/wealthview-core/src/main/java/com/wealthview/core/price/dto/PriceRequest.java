package com.wealthview.core.price.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceRequest(
        @NotBlank String symbol,
        @NotNull LocalDate date,
        @NotNull BigDecimal closePrice
) {
}
