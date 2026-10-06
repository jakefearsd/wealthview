package com.wealthview.core.price.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PriceRequest(
        @NotBlank @Size(max = 32) String symbol,
        @NotNull LocalDate date,
        @NotNull @DecimalMin("0") BigDecimal closePrice
) {
}
