package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.util.List;

public record ValuationRefreshResponse(
        String status,
        BigDecimal value,
        List<ZillowSearchResult> candidates
) {
    public static ValuationRefreshResponse updated(BigDecimal value) {
        return new ValuationRefreshResponse("updated", value, null);
    }

    public static ValuationRefreshResponse multipleMatches(List<ZillowSearchResult> candidates) {
        return new ValuationRefreshResponse("multiple_matches", null, candidates);
    }

    public static ValuationRefreshResponse noResults() {
        return new ValuationRefreshResponse("no_results", null, null);
    }
}
