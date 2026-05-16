package com.wealthview.core.price.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PriceRequest(
        @NotBlank String symbol,
        @NotNull LocalDate date,
        @NotNull BigDecimal closePrice
) {
}
