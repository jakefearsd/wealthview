package com.wealthview.api.dto;

import com.wealthview.core.property.dto.DepreciationScheduleResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DepreciationScheduleResponse(
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

    public static DepreciationScheduleResponse from(DepreciationScheduleResult result) {
        var entries = result.schedule().stream()
                .map(e -> new YearEntry(e.taxYear(), e.annualDepreciation(), e.cumulativeTaken(), e.remainingBasis()))
                .toList();
        return new DepreciationScheduleResponse(
                result.depreciationMethod(), result.depreciableBasis(),
                result.usefulLifeYears(), result.inServiceDate(), entries);
    }
}
