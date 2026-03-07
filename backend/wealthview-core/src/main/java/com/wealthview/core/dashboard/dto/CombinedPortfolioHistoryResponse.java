package com.wealthview.core.dashboard.dto;

import java.util.List;

public record CombinedPortfolioHistoryResponse(
    List<CombinedPortfolioDataPointDto> dataPoints,
    int weeks,
    int investmentAccountCount,
    int propertyCount
) {}
