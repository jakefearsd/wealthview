package com.wealthview.core.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SnapshotProjectionDataPointDto(
    int year,
    LocalDate date,
    BigDecimal totalValue,
    BigDecimal investmentValue,
    BigDecimal propertyEquity
) {}
