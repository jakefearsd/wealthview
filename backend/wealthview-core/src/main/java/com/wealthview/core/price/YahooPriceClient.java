package com.wealthview.core.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Interface for fetching prices from Yahoo Finance.
 * Implemented by YahooFinanceClient in the import module.
 */
public interface YahooPriceClient {

    record PricePoint(LocalDate date, BigDecimal closePrice) {
    }

    List<PricePoint> fetchHistory(String symbol, LocalDate from, LocalDate to);

    Optional<BigDecimal> fetchCurrentPrice(String symbol);
}
