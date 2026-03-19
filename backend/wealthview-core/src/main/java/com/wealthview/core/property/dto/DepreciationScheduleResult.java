package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DepreciationScheduleResult(
        String depreciationMethod,
        BigDecimal depreciableBasis,
        BigDecimal usefulLifeYears,
        LocalDate inServiceDate,
        List<YearEntry> schedule
) {
    public record YearEntry(
            int taxYear,
            BigDecimal annualDepreciation,
            BigDecimal cumulativeTaken,
            BigDecimal remainingBasis
    ) {}
}
