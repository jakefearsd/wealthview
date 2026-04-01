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

    record FetchResult(List<PricePoint> points, String errorReason) {

        public static FetchResult success(List<PricePoint> points) {
            return new FetchResult(points, null);
        }

        public static FetchResult failure(String reason) {
            return new FetchResult(List.of(), reason);
        }

        public boolean failed() {
            return errorReason != null;
        }
    }

    FetchResult fetchHistory(String symbol, LocalDate from, LocalDate to);

    Optional<BigDecimal> fetchCurrentPrice(String symbol);
}
