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

    /**
     * Projects deterministic per-year income (real terms) for the MC engine's full retirement horizon.
     *
     * @param retirementYearOffsetFromBase {@code retirementYear - baseYear} (audit C7 / T7-M3):
     *     the MC engine only models years from retirement onward (no accumulation phase), so its
     *     own year index {@code y} is retirement-anchored, not calendar-anchored. Adding this
     *     (possibly negative, when retirement precedes the base year) offset converts {@code y}
     *     into {@code taxYear - baseYear} — the SAME calendar clock
     *     {@link IncomeSourceProcessor}/{@link IncomeContributionCalculator} use — before it is
     *     floored at 0 and fed to {@link #realGrossForYear}.
     */
    static IncomeYearData[] computeDeterministic(List<ProjectionIncomeSourceInput> sources,
                                                 int retirementAge, int years,
                                                 int retirementYearOffsetFromBase,
                                                 double scenarioInflationRate) {
        IncomeYearData[] result = new IncomeYearData[years];
        for (int y = 0; y < years; y++) {
            result[y] = new IncomeYearData(0, 0);
        }
        if (sources == null || sources.isEmpty()) {
            return result;
        }

        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int yearsFromBase = Math.max(0, retirementYearOffsetFromBase + y);
            double totalIncome = 0;
            double taxableIncome = 0;
            for (var source : sources) {
                if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                    continue;
                }
                double amount = realGrossForYear(source, yearsFromBase, scenarioInflationRate);

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
                if (IncomeYearMath.isBoundaryAge(source, age)) {
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
     * The per-year Social Security benefit (real terms), mirroring {@link #computeDeterministic}'s
     * per-source amount logic — inflation growth, scenario deflation, and boundary-year (0.5)
     * proration — but restricted to {@link IncomeSourceType#SOCIAL_SECURITY} sources. Used by the MC
     * to split the taxable Social Security SHARE out of the naive 100%-taxable figure (audit B2).
     * {@code retirementYearOffsetFromBase}: see {@link #computeDeterministic}.
     */
    static double[] socialSecurityBenefitByYear(List<ProjectionIncomeSourceInput> sources,
                                                int retirementAge, int years,
                                                int retirementYearOffsetFromBase,
                                                double scenarioInflationRate) {
        double[] result = new double[years];
        if (sources == null || sources.isEmpty()) {
            return result;
        }
        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int yearsFromBase = Math.max(0, retirementYearOffsetFromBase + y);
            double benefit = 0;
            for (var source : sources) {
                if (source.incomeType() != IncomeSourceType.SOCIAL_SECURITY
                        || !ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                    continue;
                }
                double amount = realGrossForYear(source, yearsFromBase, scenarioInflationRate);
                if (IncomeYearMath.isBoundaryAge(source, age)) {
                    amount *= 0.5;
                }
                benefit += amount;
            }
            result[y] = benefit;
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

    /**
     * The source's REAL (today's-dollars) gross amount {@code yearsFromBase} calendar years after
     * the projection's base year (audit C7): grown by the source's own inflation, then deflated by
     * scenario inflation over the SAME calendar-anchored exponent (COLA source -> constant real at
     * every calendar year, including across the accumulation/retirement boundary; fixed-nominal
     * source -> eroded). One-time sources pay their face amount unchanged, matching
     * {@link IncomeYearMath#realAmount}.
     */
    private static double realGrossForYear(ProjectionIncomeSourceInput source, int yearsFromBase,
                                           double scenarioInflationRate) {
        double gross = source.annualAmount().doubleValue();
        if (source.oneTime()) {
            return gross;
        }
        if (source.inflationRate() != null && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
            gross *= CompoundGrowth.factor(source.inflationRate().doubleValue(), yearsFromBase);
        }
        if (scenarioInflationRate > 0 && yearsFromBase > 0) {
            gross /= CompoundGrowth.factor(scenarioInflationRate, yearsFromBase);
        }
        return gross;
    }

    private static double nullSafe(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }
}
