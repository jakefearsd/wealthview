package com.wealthview.app.it.testutil;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;

/**
 * Shared helper for the split-package ITs: applying a stock split through the
 * admin endpoint, and reading back a holding's quantity. Eliminates the
 * repeated 7-line apply-split POST and the holdings-quantity
 * BigDecimal-from-map extraction duplicated across {@code StockSplitIT},
 * {@code LateArrivingSplitIT}, and {@code StockSplitBackfillIT}.
 */
public class SplitTestSupport {

    private final ApiClient api;

    public SplitTestSupport(ApiClient api) {
        this.api = api;
    }

    /** Applies a split via {@code POST /api/v1/admin/stock-splits} as {@code token}. */
    public ResponseEntity<Map<String, Object>> applySplit(String token, String symbol, String effectiveDate,
                                                          int numerator, int denominator) {
        return api.postForEntityAs(token, "/api/v1/admin/stock-splits", Map.of(
                "symbol", symbol,
                "effective_date", effectiveDate,
                "numerator", numerator,
                "denominator", denominator));
    }

    /** The first holding's quantity for an account expected to hold exactly one symbol. */
    public BigDecimal holdingQuantity(String accountId) {
        var holdings = api.getListForEntity("/api/v1/accounts/" + accountId + "/holdings").getBody();
        return new BigDecimal(holdings.get(0).get("quantity").toString());
    }

    /** The holding matching {@code symbol} on an account, if present. */
    public Optional<Map<String, Object>> holding(String accountId, String symbol) {
        var holdings = api.getListForEntity("/api/v1/accounts/" + accountId + "/holdings").getBody();
        return holdings.stream().filter(h -> symbol.equals(h.get("symbol"))).findFirst();
    }
}
