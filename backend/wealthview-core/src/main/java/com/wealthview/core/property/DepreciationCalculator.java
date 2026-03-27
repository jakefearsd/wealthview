package com.wealthview.core.property;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.persistence.entity.PropertyEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DepreciationCalculator {

    private static final Logger log = LoggerFactory.getLogger(DepreciationCalculator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    private static final Set<String> BONUS_ELIGIBLE_CLASSES = Set.of("5yr", "7yr", "15yr");

    private static final Map<String, BigDecimal> CLASS_LIFE_YEARS = Map.of(
            "5yr", new BigDecimal("5"),
            "7yr", new BigDecimal("7"),
            "15yr", new BigDecimal("15"),
            "27_5yr", new BigDecimal("27.5"));

    /**
     * Computes a cost segregation depreciation schedule.
     * Bonus-eligible classes (5yr, 7yr, 15yr) get bonus depreciation in year 1,
     * with the remainder on straight-line over the class life.
     * Structural (27.5yr) gets straight-line only, no bonus.
     *
     * If studyYear is set and later than the in-service year, computes a
     * Section 481(a) catch-up adjustment for the study year.
     */
    public Map<Integer, BigDecimal> computeCostSegregation(
            List<CostSegAllocation> allocations, BigDecimal bonusRate,
            LocalDate inServiceDate, Integer studyYear) {
        if (allocations == null || allocations.isEmpty()) {
            return Map.of();
        }

        var schedule = new LinkedHashMap<Integer, BigDecimal>();
        boolean hasCatchUp = studyYear != null && studyYear > inServiceDate.getYear();

        for (var alloc : allocations) {
            var lifeYears = CLASS_LIFE_YEARS.get(alloc.assetClass());
            if (lifeYears == null) {
                throw new IllegalArgumentException("Invalid asset class: " + alloc.assetClass());
            }

            boolean isBonusEligible = BONUS_ELIGIBLE_CLASSES.contains(alloc.assetClass());

            if (isBonusEligible && hasCatchUp) {
                applyCatchUpSchedule(alloc, lifeYears, bonusRate, inServiceDate, studyYear, schedule);
            } else if (isBonusEligible) {
                applyBonusSchedule(alloc, lifeYears, bonusRate, inServiceDate, schedule);
            } else {
                // Structural — straight-line, no bonus
                var classSchedule = computeStraightLine(alloc.allocation(), BigDecimal.ZERO,
                        inServiceDate, lifeYears);
                for (var entry : classSchedule.entrySet()) {
                    schedule.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
                }
            }
        }

        return schedule;
    }

    private void applyBonusSchedule(CostSegAllocation alloc, BigDecimal lifeYears,
                                      BigDecimal bonusRate, LocalDate inServiceDate,
                                      Map<Integer, BigDecimal> schedule) {
        var bonusAmount = alloc.allocation().multiply(bonusRate).setScale(SCALE, ROUNDING);
        var remainder = alloc.allocation().subtract(bonusAmount);

        // Bonus goes entirely in year 1
        int startYear = inServiceDate.getYear();
        schedule.merge(startYear, bonusAmount, BigDecimal::add);

        // Remainder on straight-line over class life
        if (remainder.compareTo(BigDecimal.ZERO) > 0) {
            var classSchedule = computeStraightLine(remainder, BigDecimal.ZERO, inServiceDate, lifeYears);
            for (var entry : classSchedule.entrySet()) {
                schedule.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
            }
        }
    }

    private void applyCatchUpSchedule(CostSegAllocation alloc, BigDecimal lifeYears,
                                        BigDecimal bonusRate, LocalDate inServiceDate,
                                        int studyYear, Map<Integer, BigDecimal> schedule) {
        // Step 1: What was actually taken under 27.5yr straight-line for this portion
        var priorSLSchedule = computeStraightLine(alloc.allocation(), BigDecimal.ZERO,
                inServiceDate, new BigDecimal("27.5"));
        var priorStraightLine = BigDecimal.ZERO;
        for (int y = inServiceDate.getYear(); y < studyYear; y++) {
            priorStraightLine = priorStraightLine.add(priorSLSchedule.getOrDefault(y, BigDecimal.ZERO));
            // Include the prior-year deductions in the schedule (they were already claimed)
            schedule.merge(y, priorSLSchedule.getOrDefault(y, BigDecimal.ZERO), BigDecimal::add);
        }

        // Step 2: What should have been taken under accelerated method
        var shouldHaveTakenSchedule = new LinkedHashMap<Integer, BigDecimal>();
        applyBonusSchedule(alloc, lifeYears, bonusRate, inServiceDate, shouldHaveTakenSchedule);
        var shouldHaveTaken = BigDecimal.ZERO;
        for (int y = inServiceDate.getYear(); y < studyYear; y++) {
            shouldHaveTaken = shouldHaveTaken.add(shouldHaveTakenSchedule.getOrDefault(y, BigDecimal.ZERO));
        }

        // Step 3: 481(a) adjustment = shouldHaveTaken - priorStraightLine → added to studyYear
        var adjustment = shouldHaveTaken.subtract(priorStraightLine);
        schedule.merge(studyYear, adjustment, BigDecimal::add);

        // Step 4: From studyYear onward, continue the accelerated schedule
        for (var entry : shouldHaveTakenSchedule.entrySet()) {
            if (entry.getKey() >= studyYear) {
                schedule.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
            }
        }
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

    /**
     * Computes the full depreciation schedule for a property based on its method.
     * This is the Strategy dispatch point — callers no longer need to check the method string.
     *
     * @return depreciation schedule (year -> amount), or empty map for "none"
     */
    public Map<Integer, BigDecimal> computeSchedule(PropertyEntity property) {
        var method = property.getDepreciationMethod();
        if (method == null || "none".equals(method)) {
            return Map.of();
        }

        var landValue = property.getLandValue() != null ? property.getLandValue() : BigDecimal.ZERO;
        var inServiceDate = property.getInServiceDate();
        if (inServiceDate == null) {
            return Map.of();
        }

        if ("cost_segregation".equals(method)) {
            var allocations = parseCostSegAllocations(property.getCostSegAllocations());
            if (allocations.isEmpty()) {
                return Map.of();
            }
            return computeCostSegregation(allocations, property.getBonusDepreciationRate(),
                    inServiceDate, property.getCostSegStudyYear());
        }

        // straight_line
        return computeStraightLine(property.getPurchasePrice(), landValue,
                inServiceDate, property.getUsefulLifeYears());
    }

    /**
     * Sums depreciation from a schedule through a given year (inclusive).
     */
    public static BigDecimal accumulatedThrough(Map<Integer, BigDecimal> schedule, int throughYear) {
        var total = BigDecimal.ZERO;
        for (var entry : schedule.entrySet()) {
            if (entry.getKey() <= throughYear) {
                total = total.add(entry.getValue());
            }
        }
        return total.setScale(SCALE, ROUNDING);
    }

    private static List<CostSegAllocation> parseCostSegAllocations(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse cost_seg_allocations JSON", e);
            return List.of();
        }
    }
}
