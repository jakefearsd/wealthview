package com.wealthview.core.price.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;

public record BulkPriceRequest(@NotEmpty List<PriceEntry> prices) {

    public record PriceEntry(String symbol, LocalDate date, BigDecimal closePrice) {
    }
}
