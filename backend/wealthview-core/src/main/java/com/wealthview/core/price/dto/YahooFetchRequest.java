package com.wealthview.core.price.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record YahooFetchRequest(
        @NotEmpty List<String> symbols,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate
) {
}
