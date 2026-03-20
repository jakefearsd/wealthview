package com.wealthview.api.dto;

import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.core.property.dto.DepreciationScheduleResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DepreciationScheduleResponse(
        String depreciationMethod,
        BigDecimal depreciableBasis,
        BigDecimal usefulLifeYears,
        LocalDate inServiceDate,
        List<YearEntry> schedule,
        BigDecimal bonusDepreciationRate,
        List<CostSegAllocation> costSegAllocations,
        List<ClassBreakdown> classBreakdowns
) {
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

    public static DepreciationScheduleResponse from(DepreciationScheduleResult result) {
        var entries = result.schedule().stream()
                .map(e -> new YearEntry(e.taxYear(), e.annualDepreciation(), e.cumulativeTaken(), e.remainingBasis()))
                .toList();
        var breakdowns = result.classBreakdowns() == null ? null : result.classBreakdowns().stream()
                .map(b -> new ClassBreakdown(b.assetClass(), b.lifeYears(), b.allocation(),
                        b.bonusAmount(), b.annualStraightLine(), b.straightLineYears()))
                .toList();
        return new DepreciationScheduleResponse(
                result.depreciationMethod(), result.depreciableBasis(),
                result.usefulLifeYears(), result.inServiceDate(), entries,
                result.bonusDepreciationRate(), result.costSegAllocations(), breakdowns);
    }
}
