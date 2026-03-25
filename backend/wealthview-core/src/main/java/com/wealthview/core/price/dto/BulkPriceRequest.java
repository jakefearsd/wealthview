package com.wealthview.core.price.dto;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BulkPriceRequest(@NotEmpty List<PriceEntry> prices) {

    public record PriceEntry(String symbol, LocalDate date, BigDecimal closePrice) {
    }
}
