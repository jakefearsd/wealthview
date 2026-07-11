package com.wealthview.projection;

import java.util.Arrays;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Immutable parameter object holding every input to a Roth conversion optimization.
 *
 * <p>Collapses what was previously a 20-argument constructor / 20-field set on
 * {@link RothConversionOptimizer} into a single value carrier shared by the
 * orchestrator and its collaborators ({@link ConversionSimulator},
 * {@link FractionSearch}).
 *
 * <p>{@code years} and {@code rmdStartAge} are derived once at construction so
 * collaborators do not recompute them. The income arrays are defensively copied.
 */
record RothConversionConfig(
        double initTraditional,
        double initRoth,
        double initTaxable,
        double[] otherIncomeByYear,
        double[] taxableIncomeByYear,
        int birthYear,
        int retirementAge,
        int endAge,
        int exhaustionBuffer,
        double conversionBracketRate,
        double rmdTargetBracketRate,
        double returnMean,
        double essentialFloor,
        FilingStatus filingStatus,
        FederalTaxCalculator taxCalculator,
        String withdrawalOrder,
        double rmdBracketHeadroom,
        double dynamicSequencingBracketRate,
        int years,
        int rmdStartAge,
        RentalAdjustmentCalculator rentalAdjustmentCalculator) {

    RothConversionConfig {
        otherIncomeByYear = Arrays.copyOf(otherIncomeByYear, otherIncomeByYear.length);
        taxableIncomeByYear = Arrays.copyOf(taxableIncomeByYear, taxableIncomeByYear.length);
    }

    @Override
    public double[] otherIncomeByYear() {
        return Arrays.copyOf(otherIncomeByYear, otherIncomeByYear.length);
    }

    @Override
    public double[] taxableIncomeByYear() {
        return Arrays.copyOf(taxableIncomeByYear, taxableIncomeByYear.length);
    }
}
