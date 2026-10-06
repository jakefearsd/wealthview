package com.wealthview.projection;

import java.math.BigDecimal;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;

/**
 * Shared rental income computation used by both MonteCarloSpendingOptimizer
 * and RothConversionOptimizer.
 */
final class RentalIncomeHelper {

    private RentalIncomeHelper() {}

    record RentalYearResult(double netTaxableIncome, BigDecimal newSuspendedLoss) {}

    /**
     * Computes the net taxable rental income for a single source in a single year,
     * applying inflation, expenses, depreciation, mortgage interest, and passive loss rules.
     * The interest is {@code calendarYear}'s entry in the source's nominal mortgage schedule
     * (zero after payoff), deflated to today's dollars over {@code yearsFromBase} calendar
     * years since the projection's base year (0 = the base year) at
     * {@code scenarioInflationRate}.
     */
    static RentalYearResult computeForSource(ProjectionIncomeSourceInput source,
                                             int yearIndex, int calendarYear,
                                             int yearsFromBase, double scenarioInflationRate,
                                             double magi, BigDecimal priorSuspendedLoss,
                                             RentalLossCalculator calculator) {
        double gross = source.annualAmount().doubleValue();
        if (source.inflationRate() != null
                && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
            gross *= CompoundGrowth.factor(source.inflationRate().doubleValue(), yearIndex);
        }

        double expenses = nullSafe(source.annualOperatingExpenses())
                + nullSafe(source.annualPropertyTax());
        double depreciation = 0;
        if (source.depreciationByYear() != null) {
            var depBd = source.depreciationByYear().get(calendarYear);
            if (depBd != null) {
                depreciation = depBd.doubleValue();
            }
        }
        double mortgageInterest = realFixedNominal(
                source.mortgageInterestIn(calendarYear).doubleValue(), yearsFromBase, scenarioInflationRate);
        double netRentalIncome = gross - expenses - mortgageInterest - depreciation;

        var lossResult = calculator.applyLossRules(
                BigDecimal.valueOf(netRentalIncome),
                source.taxTreatment(),
                BigDecimal.ZERO,
                BigDecimal.valueOf(Math.max(0, magi)),
                priorSuspendedLoss);

        return new RentalYearResult(
                lossResult.netTaxableIncome().doubleValue(),
                lossResult.lossSuspended());
    }

    /**
     * The real (today's-dollars) value of a fixed-nominal amount paid {@code yearsFromBase} calendar
     * years after the projection's base year (0 = the base year) -- the double-precision, 0-indexed
     * counterpart of {@link IncomeYearMath#realFixedNominal}, on the same clock
     * {@link IncomeProjector} deflates every other source with.
     */
    static double realFixedNominal(double nominal, int yearsFromBase, double scenarioInflationRate) {
        if (yearsFromBase <= 0 || scenarioInflationRate <= 0) {
            return nominal;
        }
        return nominal / CompoundGrowth.factor(scenarioInflationRate, yearsFromBase);
    }

    static double nullSafe(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }
}
