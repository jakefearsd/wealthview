package com.wealthview.core.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The dashboard's forward-projection card.
 *
 * @param unconvertedAccounts names of accounts left out of the projection because their currency
 *                            has no exchange rate, matching {@code DashboardSummaryResponse}
 */
public record SnapshotProjectionResponse(
        List<SnapshotProjectionDataPointDto> dataPoints,
        int projectionYears,
        int investmentAccountCount,
        int propertyCount,
        BigDecimal portfolioCagr,
        List<String> unconvertedAccounts
) {}
