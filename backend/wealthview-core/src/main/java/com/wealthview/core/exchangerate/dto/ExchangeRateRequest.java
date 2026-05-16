package com.wealthview.core.exchangerate.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ExchangeRateRequest(
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rateToUsd
) {
}
