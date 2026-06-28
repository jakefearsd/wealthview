package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;

/**
 * Projects deterministic per-year retirement income from the configured income sources, including
 * inflation growth, boundary-year proration, rental net-cash-flow handling, and rental-aware
 * taxable-income adjustments (depreciation, passive-loss rules, suspended-loss carryforward).
 * Pure functions extracted from {@link OptimizationContextBuilder}.
 */
final class IncomeProjector {

    private IncomeProjector() {
    }

    static IncomeYearData[] computeDeterministic(List<ProjectionIncomeSourceInput> sources,
                                                 int retirementAge, int years) {
        IncomeYearData[] result = new IncomeYearData[years];
        for (int y = 0; y < years; y++) {
            result[y] = new IncomeYearData(0, 0);
        }
        if (sources == null || sources.isEmpty()) {
            return result;
        }

        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int yearsInRetirement = y + 1;
            double totalIncome = 0;
            double taxableIncome = 0;
            for (var source : sources) {
                if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                    continue;
                }
                double gross = source.annualAmount().doubleValue();

                if (source.inflationRate() != null
                        && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
                    gross *= CompoundGrowth.factor(source.inflationRate().doubleValue(), yearsInRetirement - 1);
                }

                double amount = gross;

                // For rental properties, subtract all cash outflows to get net cash flow,
                // matching IncomeSourceProcessor: operating expenses, mortgage interest,
                // property tax, AND mortgage principal (principal reduces available cash even
                // though it is not tax-deductible).
                if (source.incomeType() == IncomeSourceType.RENTAL_PROPERTY) {
                    amount -= nullSafe(source.annualOperatingExpenses());
                    amount -= nullSafe(source.annualMortgageInterest());
                    amount -= nullSafe(source.annualPropertyTax());
                    amount -= nullSafe(source.annualMortgagePrincipal());
                    amount = Math.max(0, amount);
                }

                // Apply boundary multiplier (0.5 at startAge/endAge) for recurring sources only.
                // One-time sources pay their full amount at startAge, matching ICC and ISP.
                if (!source.oneTime()
                        && (age == source.startAge()
                                || (source.endAge() != null && age == source.endAge()))) {
                    amount *= 0.5;
                }
                totalIncome += amount;

                // All non-rental income is treated as taxable for MC purposes.
                // Rental net cash is excluded (complex passive-loss rules not applicable here).
                if (source.incomeType() != IncomeSourceType.RENTAL_PROPERTY) {
                    taxableIncome += amount;
                }
            }
            result[y] = new IncomeYearData(totalIncome, taxableIncome);
        }
        return result;
    }

    /**
     * Enhances taxableIncomeByYear with rental property effects: depreciation deductions, passive
     * loss rules, and suspended loss carryforward, giving the MC trial withdrawal-tax estimates a
     * more accurate baseline income.
     */
    static double[] computeRentalAwareTaxable(double[] baseTaxableIncome,
                                              List<ProjectionIncomeSourceInput> sources,
                                              int retirementAge, int birthYear, int years) {
        double[] result = Arrays.copyOf(baseTaxableIncome, years);
        if (sources == null || sources.isEmpty()) {
            return result;
        }

        var rentalSources = sources.stream()
                .filter(s -> s.incomeType() == IncomeSourceType.RENTAL_PROPERTY)
                .toList();
        if (rentalSources.isEmpty()) {
            return result;
        }

        var calculator = new RentalLossCalculator();
        var suspendedBySource = new HashMap<ProjectionIncomeSourceInput, BigDecimal>();
        for (var source : rentalSources) {
            suspendedBySource.put(source, BigDecimal.ZERO);
        }

        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int calendarYear = birthYear + age;
            double baseOtherIncome = y < baseTaxableIncome.length ? baseTaxableIncome[y] : 0;
            double yearAdjustment = 0;

            for (var source : rentalSources) {
                if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                    continue;
                }
                var rentalResult = RentalIncomeHelper.computeForSource(
                        source, y, calendarYear, baseOtherIncome,
                        suspendedBySource.get(source), calculator);
                suspendedBySource.put(source, rentalResult.newSuspendedLoss());
                yearAdjustment += rentalResult.netTaxableIncome();
            }

            result[y] = Math.max(0, result[y] + yearAdjustment);
        }

        return result;
    }

    private static double nullSafe(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }
}
