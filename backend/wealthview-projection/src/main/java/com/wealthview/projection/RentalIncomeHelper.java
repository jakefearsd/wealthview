package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;

import java.math.BigDecimal;

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
     */
    static RentalYearResult computeForSource(ProjectionIncomeSourceInput source,
                                             int yearIndex, int calendarYear,
                                             double magi, BigDecimal priorSuspendedLoss,
                                             RentalLossCalculator calculator) {
        double gross = source.annualAmount().doubleValue();
        if (source.inflationRate() != null
                && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
            gross *= Math.pow(1 + source.inflationRate().doubleValue(), yearIndex);
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
        double mortgageInterest = nullSafe(source.annualMortgageInterest());
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

    static double nullSafe(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }
}
