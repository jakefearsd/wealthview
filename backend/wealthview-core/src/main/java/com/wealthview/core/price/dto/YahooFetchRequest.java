package com.wealthview.core.price.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record YahooFetchRequest(
        @NotEmpty List<String> symbols,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate
) {
}
