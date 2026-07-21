package com.wealthview.core.property;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wealthview.core.common.Money;
import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.core.property.dto.DepreciationScheduleResult;
import com.wealthview.persistence.entity.PropertyEntity;

/**
 * Builds the read-side {@link DepreciationScheduleResult} for a property.
 *
 * <p>Extracted from {@code PropertyService} (via {@code PropertyDepreciationService}) as
 * the schedule-construction collaborator. It does no data access and mutates no entity:
 * given a resolved {@link PropertyEntity} it produces the year-by-year schedule and the
 * per-asset-class breakdown. {@code PropertyService} owns the {@code @Transactional}
 * read boundary and tenant-scoped lookup.
 */
class DepreciationScheduleBuilder {

    private final DepreciationCalculator depreciationCalculator;

    DepreciationScheduleBuilder(DepreciationCalculator depreciationCalculator) {
        this.depreciationCalculator = depreciationCalculator;
    }

    DepreciationScheduleResult buildSchedule(PropertyEntity property) {
        var method = property.getDepreciationMethod();
        if (method == null || "none".equals(method)) {
            throw new IllegalArgumentException("Depreciation is not configured for this property");
        }

        var landValue = property.getLandValue() != null ? property.getLandValue() : BigDecimal.ZERO;
        var depreciableBasis = property.getPurchasePrice().subtract(landValue);

        if ("cost_segregation".equals(method)) {
            return buildCostSegScheduleResult(property, depreciableBasis);
        }

        var schedule = depreciationCalculator.computeStraightLine(
                property.getPurchasePrice(), landValue,
                property.getInServiceDate(), property.getUsefulLifeYears());

        return buildScheduleResult(method, depreciableBasis, property.getUsefulLifeYears(),
                property.getInServiceDate(), schedule);
    }

    private DepreciationScheduleResult buildScheduleResult(String method, BigDecimal depreciableBasis,
                                                             BigDecimal usefulLifeYears, LocalDate inServiceDate,
                                                             Map<Integer, BigDecimal> schedule) {
        var entries = buildYearEntries(depreciableBasis, schedule);
        return new DepreciationScheduleResult(method, depreciableBasis, usefulLifeYears, inServiceDate, entries);
    }

    private DepreciationScheduleResult buildCostSegScheduleResult(
            PropertyEntity property, BigDecimal depreciableBasis) {
        var allocations = PropertyDepreciationService.parseCostSegAllocations(property.getCostSegAllocations());
        var bonusRate = property.getBonusDepreciationRate();
        var schedule = depreciationCalculator.computeCostSegregation(
                allocations, bonusRate, property.getInServiceDate(), property.getCostSegStudyYear());

        var entries = buildYearEntries(depreciableBasis, schedule);
        var classBreakdowns = buildClassBreakdowns(allocations, bonusRate);

        return new DepreciationScheduleResult(
                property.getDepreciationMethod(),
                depreciableBasis,
                property.getUsefulLifeYears(),
                property.getInServiceDate(),
                entries,
                bonusRate,
                allocations,
                classBreakdowns);
    }

    private List<DepreciationScheduleResult.YearEntry> buildYearEntries(BigDecimal depreciableBasis,
                                                                         Map<Integer, BigDecimal> schedule) {
        var cumulative = BigDecimal.ZERO;
        var entries = new ArrayList<DepreciationScheduleResult.YearEntry>();
        for (var entry : schedule.entrySet()) {
            cumulative = cumulative.add(entry.getValue());
            entries.add(new DepreciationScheduleResult.YearEntry(
                    entry.getKey(),
                    entry.getValue(),
                    cumulative,
                    depreciableBasis.subtract(cumulative)));
        }
        return entries;
    }

    private List<DepreciationScheduleResult.ClassBreakdown> buildClassBreakdowns(
            List<CostSegAllocation> allocations, BigDecimal bonusRate) {
        var breakdowns = new ArrayList<DepreciationScheduleResult.ClassBreakdown>();
        for (var alloc : allocations) {
            var lifeYears = DepreciationCalculator.classLifeYears(alloc.assetClass());
            boolean isBonusEligible = DepreciationCalculator.isBonusEligible(alloc.assetClass());
            var bonusAmount = isBonusEligible
                    ? alloc.allocation().multiply(bonusRate).setScale(Money.SCALE, Money.ROUNDING)
                    : BigDecimal.ZERO;
            var remainder = alloc.allocation().subtract(bonusAmount);
            var annualSL = remainder.compareTo(BigDecimal.ZERO) > 0
                    ? remainder.divide(lifeYears, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            int slYears = remainder.compareTo(BigDecimal.ZERO) > 0
                    ? lifeYears.intValue() + 1 // includes partial first/last year
                    : 0;
            breakdowns.add(new DepreciationScheduleResult.ClassBreakdown(
                    alloc.assetClass(), lifeYears, alloc.allocation(),
                    bonusAmount, annualSL, slYears));
        }
        return breakdowns;
    }
}
