package com.wealthview.core.pricefeed.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CandleResponse(String symbol, List<CandleEntry> entries) {

    public record CandleEntry(LocalDate date, BigDecimal closePrice) {}
}
