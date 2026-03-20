package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DepreciationScheduleResult(
        String depreciationMethod,
        BigDecimal depreciableBasis,
        BigDecimal usefulLifeYears,
        LocalDate inServiceDate,
        List<YearEntry> schedule,
        BigDecimal bonusDepreciationRate,
        List<CostSegAllocation> costSegAllocations,
        List<ClassBreakdown> classBreakdowns
) {
    public DepreciationScheduleResult(String depreciationMethod, BigDecimal depreciableBasis,
                                       BigDecimal usefulLifeYears, LocalDate inServiceDate,
                                       List<YearEntry> schedule) {
        this(depreciationMethod, depreciableBasis, usefulLifeYears, inServiceDate,
                schedule, null, null, null);
    }

    public record YearEntry(
            int taxYear,
            BigDecimal annualDepreciation,
            BigDecimal cumulativeTaken,
            BigDecimal remainingBasis
    ) {}

    public record ClassBreakdown(
            String assetClass,
            BigDecimal lifeYears,
            BigDecimal allocation,
            BigDecimal bonusAmount,
            BigDecimal annualStraightLine,
            int straightLineYears
    ) {}
}
