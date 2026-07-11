package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;

/**
 * Finds the optimal Roth conversion schedule that minimizes lifetime tax.
 *
 * <p>Thin orchestrator: it derives a {@link RothConversionConfig}, computes the
 * target traditional balance at RMD age, and delegates the year-by-year
 * simulation to {@link ConversionSimulator} and the conversion-fraction search
 * to {@link FractionSearch}. The optimizer holds no random state — identical
 * inputs always yield an identical schedule.
 *
 * <p>Package-private — not a Spring bean; instantiated by the service layer.
 */
final class RothConversionOptimizer {

    private static final Logger log = LoggerFactory.getLogger(RothConversionOptimizer.class);

    private final RothConversionConfig config;
    private final double targetTraditionalBalance;
    private final ConversionSimulator simulator;
    private final FractionSearch fractionSearch;

    /**
     * Lazily computed no-conversion simulation. ConversionSimulator is pure, so
     * the fraction-0 result never changes for a given optimizer instance —
     * without this cache the joint search recomputed it on every one of its
     * ~41 scheduleForFraction evaluations.
     */
    private SimResult baselineResult;

    record RothConversionSchedule(
            double[] conversionByYear,
            double[] conversionTaxByYear,
            double[] traditionalBalance,
            double[] rothBalance,
            double[] taxableBalance,
            double[] projectedRmd,
            double lifetimeTaxWith,
            double lifetimeTaxWithout,
            int exhaustionAge,
            boolean exhaustionTargetMet,
            double conversionFraction,
            double targetTraditionalBalance
    ) {}

    private RothConversionOptimizer(double initTraditional, double initRoth, double initTaxable,
                            double[] otherIncomeByYear, double[] taxableIncomeByYear,
                            int birthYear, int retirementAge, int endAge,
                            int exhaustionBuffer, double conversionBracketRate,
                            double rmdTargetBracketRate, double returnMean,
                            double essentialFloor,
                            FilingStatus filingStatus, FederalTaxCalculator taxCalculator,
                            String withdrawalOrder,
                            List<ProjectionIncomeSourceInput> incomeSources,
                            RentalLossCalculator rentalLossCalculator,
                            double rmdBracketHeadroom,
                            double dynamicSequencingBracketRate) {
        var rentalAdjustmentCalculator = new RentalAdjustmentCalculator(
                incomeSources, rentalLossCalculator, birthYear, retirementAge);
        this.config = new RothConversionConfig(
                initTraditional, initRoth, initTaxable,
                otherIncomeByYear, taxableIncomeByYear,
                birthYear, retirementAge, endAge, exhaustionBuffer,
                conversionBracketRate, rmdTargetBracketRate, returnMean,
                essentialFloor, filingStatus, taxCalculator,
                withdrawalOrder, rmdBracketHeadroom, dynamicSequencingBracketRate,
                endAge - retirementAge, RmdCalculator.rmdStartAge(birthYear),
                rentalAdjustmentCalculator);
        this.targetTraditionalBalance = computeTargetTraditionalBalance();
        this.simulator = new ConversionSimulator(config, targetTraditionalBalance);
        this.fractionSearch = new FractionSearch(simulator, targetTraditionalBalance,
                config.rmdStartAge(), config.retirementAge());
    }

    /**
     * Computes the target traditional IRA balance at RMD start age such that
     * RMDs stay within the user's target bracket (with headroom for market variability).
     *
     * <p>Formula: targetBalance = availableForRmd × distributionPeriod(rmdStartAge)
     * where availableForRmd = grossBracketCeiling × (1 - headroom) - otherIncomeAtRmdAge.
     */
    private double computeTargetTraditionalBalance() {
        int rmdStartAge = config.rmdStartAge();
        int retirementAge = config.retirementAge();
        int birthYear = config.birthYear();
        double rmdTargetBracketRate = config.rmdTargetBracketRate();
        double rmdBracketHeadroom = config.rmdBracketHeadroom();

        double distributionPeriod = RmdCalculator.distributionPeriod(rmdStartAge);
        if (distributionPeriod <= 0) {
            log.warn("Target balance: distributionPeriod <= 0 for rmdStartAge={}", rmdStartAge);
            return 0;
        }

        // Estimate taxable income at RMD start age (excludes rental cash flow,
        // which is not directly taxable — rental tax effects are handled separately
        // via RentalLossCalculator). Using otherIncomeByYear here would include
        // gross rental cash, inflating the "other income" and leaving no room for RMDs.
        int rmdYearIndex = rmdStartAge - retirementAge;
        double[] taxableIncomeByYear = config.taxableIncomeByYear();
        double otherIncomeAtRmd = rmdYearIndex >= 0 && rmdYearIndex < taxableIncomeByYear.length
                ? taxableIncomeByYear[rmdYearIndex] : 0;
        // Add rental tax adjustment at RMD age (can be negative for losses)
        var rentalAdjustmentCalculator = config.rentalAdjustmentCalculator();
        if (!rentalAdjustmentCalculator.isEmpty()) {
            var tempSuspended = rentalAdjustmentCalculator.initSuspendedLosses();
            double rentalAdj = rentalAdjustmentCalculator.adjustmentForYear(
                    rmdYearIndex, otherIncomeAtRmd, tempSuspended);
            otherIncomeAtRmd += rentalAdj;
        }

        // Bracket ceiling with headroom. Real-terms: brackets are constant real, so the ceiling is
        // NOT inflation-indexed.
        int rmdCalendarYear = birthYear + rmdStartAge;
        double grossCeiling = config.taxCalculator().computeMaxIncomeForBracket(
                BigDecimal.valueOf(rmdTargetBracketRate), rmdCalendarYear, config.filingStatus(),
                BigDecimal.ZERO).doubleValue();
        double availableForRmd = grossCeiling * (1 - rmdBracketHeadroom) - otherIncomeAtRmd;

        log.info("Target balance computation: rmdStartAge={}, distributionPeriod={}, "
                + "rmdYearIndex={}, otherIncomeAtRmd={}, rmdCalendarYear={}, "
                + "rmdTargetBracketRate={}, grossCeiling={}, headroom={}, availableForRmd={}, "
                + "result={}",
                rmdStartAge, distributionPeriod, rmdYearIndex, otherIncomeAtRmd,
                rmdCalendarYear, rmdTargetBracketRate, grossCeiling, rmdBracketHeadroom,
                availableForRmd, availableForRmd > 0 ? availableForRmd * distributionPeriod : 0);

        if (availableForRmd <= 0) {
            return 0;
        }
        return availableForRmd * distributionPeriod;
    }

    private SimResult baseline() {
        if (baselineResult == null) {
            baselineResult = simulator.simulateForFraction(0.0);
        }
        return baselineResult;
    }

    RothConversionSchedule optimize() {
        var baseline = baseline();

        if (config.initTraditional() <= 0) {
            return buildSchedule(baseline, baseline.lifetimeTax(), 0.0);
        }

        double bestFraction = fractionSearch.findOptimalFraction(baseline);
        var best = simulator.simulateForFraction(bestFraction);

        return buildSchedule(best, baseline.lifetimeTax(), bestFraction);
    }

    /**
     * Produces the conversion schedule for a specific fraction. Used by the MC optimizer's
     * joint search loop, which scores fractions by sustainable spending rather than lifetime tax.
     */
    RothConversionSchedule scheduleForFraction(double fraction) {
        var result = fraction == 0.0 ? baseline() : simulator.simulateForFraction(fraction);
        return buildSchedule(result, baseline().lifetimeTax(), fraction);
    }

    /**
     * Returns the baseline (no conversion) schedule.
     */
    RothConversionSchedule baselineSchedule() {
        return buildSchedule(baseline(), baseline().lifetimeTax(), 0.0);
    }

    private RothConversionSchedule buildSchedule(SimResult result, double baselineTax,
                                                   double fraction) {
        int exhaustionTarget = config.endAge() - config.exhaustionBuffer();
        return new RothConversionSchedule(
                result.conversionByYear(),
                result.conversionTaxByYear(),
                result.traditionalBalance(),
                result.rothBalance(),
                result.taxableBalance(),
                result.projectedRmd(),
                result.lifetimeTax(),
                baselineTax,
                result.exhaustionAge(),
                result.exhaustionAge() <= exhaustionTarget,
                fraction,
                targetTraditionalBalance
        );
    }

    static Builder builder() {
        return new Builder();
    }

    // TooManyFields: a builder holds one staging field per constructor argument of the object it
    // builds; RothConversionOptimizer genuinely needs this many inputs, so the count is inherent.
    @SuppressWarnings("PMD.TooManyFields")
    static class Builder {
        private double traditional;
        private double roth;
        private double taxable;
        private double[] otherIncomeByYear;
        private double[] taxableIncomeByYear;
        private int birthYear;
        private int retirementAge;
        private int endAge;
        private int exhaustionBuffer;
        private double conversionBracketRate;
        private double rmdTargetBracketRate;
        private double returnMean;
        private double essentialFloor;
        private double rmdBracketHeadroom;
        private double dynamicSequencingBracketRate = 0.0;
        private FilingStatus filingStatus;
        private FederalTaxCalculator taxCalculator;
        private String withdrawalOrder;
        private List<ProjectionIncomeSourceInput> incomeSources;
        private RentalLossCalculator rentalLossCalculator;

        Builder portfolio(double traditional, double roth, double taxable) {
            this.traditional = traditional;
            this.roth = roth;
            this.taxable = taxable;
            return this;
        }

        // UseVarargs: both params are distinct per-year indexed arrays — making the last one
        // varargs would let callers omit it or pass loose values, breaking the builder contract.
        @SuppressWarnings("PMD.UseVarargs")
        Builder income(double[] otherIncome, double[] taxableIncome) {
            this.otherIncomeByYear = Arrays.copyOf(otherIncome, otherIncome.length);
            this.taxableIncomeByYear = Arrays.copyOf(taxableIncome, taxableIncome.length);
            return this;
        }

        Builder demographics(int birthYear, int retirementAge, int endAge) {
            this.birthYear = birthYear;
            this.retirementAge = retirementAge;
            this.endAge = endAge;
            return this;
        }

        Builder taxConfig(double convBracket, double rmdTarget, double headroom,
                          FilingStatus status, FederalTaxCalculator calc) {
            this.conversionBracketRate = convBracket;
            this.rmdTargetBracketRate = rmdTarget;
            this.rmdBracketHeadroom = headroom;
            this.filingStatus = status;
            this.taxCalculator = calc;
            return this;
        }

        Builder assumptions(double returnMean, double essentialFloor,
                            int exhaustionBuffer,
                            String withdrawalOrder) {
            this.returnMean = returnMean;
            this.essentialFloor = essentialFloor;
            this.exhaustionBuffer = exhaustionBuffer;
            this.withdrawalOrder = withdrawalOrder;
            return this;
        }

        Builder rentals(List<ProjectionIncomeSourceInput> sources,
                        RentalLossCalculator calc) {
            this.incomeSources = sources;
            this.rentalLossCalculator = calc;
            return this;
        }

        Builder dynamicSequencingBracketRate(double rate) {
            this.dynamicSequencingBracketRate = rate;
            return this;
        }

        RothConversionOptimizer build() {
            return new RothConversionOptimizer(
                    traditional, roth, taxable,
                    otherIncomeByYear, taxableIncomeByYear,
                    birthYear, retirementAge, endAge, exhaustionBuffer,
                    conversionBracketRate, rmdTargetBracketRate, returnMean,
                    essentialFloor, filingStatus, taxCalculator,
                    withdrawalOrder, incomeSources, rentalLossCalculator,
                    rmdBracketHeadroom, dynamicSequencingBracketRate);
        }
    }
}
