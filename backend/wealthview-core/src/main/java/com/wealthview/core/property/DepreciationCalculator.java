package com.wealthview.core.property;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DepreciationCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * Computes a straight-line depreciation schedule using IRS mid-month convention.
     * First year is prorated based on the month placed in service.
     * Last partial year gets the remainder so total equals depreciable basis.
     *
     * @return map of tax year → depreciation amount
     */
    public Map<Integer, BigDecimal> computeStraightLine(BigDecimal purchasePrice, BigDecimal landValue,
                                                         LocalDate inServiceDate, BigDecimal usefulLifeYears) {
        var depreciableBasis = purchasePrice.subtract(landValue);
        if (depreciableBasis.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of();
        }

        var annualAmount = depreciableBasis.divide(usefulLifeYears, SCALE, ROUNDING);

        // Mid-month convention: month placed in service counts as half month
        int monthPlaced = inServiceDate.getMonthValue();
        // Remaining months including half of placement month
        BigDecimal remainingMonths = new BigDecimal(12 - monthPlaced).add(new BigDecimal("0.5"));
        var firstYearAmount = annualAmount.multiply(remainingMonths)
                .divide(new BigDecimal("12"), SCALE, ROUNDING);

        var schedule = new LinkedHashMap<Integer, BigDecimal>();
        int startYear = inServiceDate.getYear();
        schedule.put(startYear, firstYearAmount);

        var totalDepreciated = firstYearAmount;
        int year = startYear + 1;

        while (totalDepreciated.add(annualAmount).compareTo(depreciableBasis) < 0) {
            schedule.put(year, annualAmount);
            totalDepreciated = totalDepreciated.add(annualAmount);
            year++;
        }

        // Last year gets the remainder
        var remainder = depreciableBasis.subtract(totalDepreciated);
        if (remainder.compareTo(BigDecimal.ZERO) > 0) {
            schedule.put(year, remainder.setScale(SCALE, ROUNDING));
        }

        return schedule;
    }

    /**
     * Gets the depreciation amount for a specific tax year.
     */
    public BigDecimal getDepreciationForYear(String depreciationMethod,
                                              BigDecimal purchasePrice, BigDecimal landValue,
                                              LocalDate inServiceDate, BigDecimal usefulLifeYears,
                                              int taxYear,
                                              Map<Integer, BigDecimal> costSegSchedule) {
        if ("none".equals(depreciationMethod)) {
            return BigDecimal.ZERO;
        }

        if (taxYear < inServiceDate.getYear()) {
            return BigDecimal.ZERO;
        }

        if ("cost_segregation".equals(depreciationMethod)) {
            return costSegSchedule.getOrDefault(taxYear, BigDecimal.ZERO);
        }

        // straight_line
        var schedule = computeStraightLine(purchasePrice, landValue, inServiceDate, usefulLifeYears);
        return schedule.getOrDefault(taxYear, BigDecimal.ZERO);
    }
}
