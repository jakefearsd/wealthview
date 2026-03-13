package com.wealthview.core.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record SnapshotProjectionResponse(
    List<SnapshotProjectionDataPointDto> dataPoints,
    int projectionYears,
    int investmentAccountCount,
    int propertyCount,
    BigDecimal portfolioCagr
) {}
