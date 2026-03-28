package com.wealthview.projection;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the optimal Roth conversion schedule that minimizes lifetime tax.
 * Uses a hybrid grid scan + ternary refinement over conversion fraction.
 * Package-private — not a Spring bean; instantiated by the service layer.
 */
class RothConversionOptimizer {

    private static final Logger log = LoggerFactory.getLogger(RothConversionOptimizer.class);
    private static final int GRID_SIZE = 50;
    private static final double REFINE_HALF_WIDTH = 0.05;
    private static final int REFINE_ITERATIONS = 20;
    private static final int EARLY_WITHDRAWAL_AGE = 60; // proxy for 59.5
    private static final int MAGI_CONVERGENCE_ITERATIONS = 3;
    private static final double CONVERGENCE_THRESHOLD_DOLLARS = 100;
    private static final double FEASIBILITY_TOLERANCE = 1.05;
    /** Binary search iterations used to find the maximum affordable conversion amount. */
    private static final int AFFORDABILITY_BINARY_SEARCH_ITERATIONS = 30;

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

    private final double initTraditional;
    private final double initRoth;
    private final double initTaxable;
    private final double[] otherIncomeByYear;
    private final double[] taxableIncomeByYear;
    private final int birthYear;
    private final int retirementAge;
    private final int endAge;
    private final int exhaustionBuffer;
    private final double conversionBracketRate;
    private final double rmdTargetBracketRate;
    private final double returnMean;
    private final double essentialFloor;
    private final double inflationRate;
    private final FilingStatus filingStatus;
    private final FederalTaxCalculator taxCalculator;
    private final String withdrawalOrder;
    private final int years;
    private final int rmdStartAge;
    private final List<ProjectionIncomeSourceInput> rentalSources;
    private final RentalLossCalculator rentalLossCalculator;
    private final double rmdBracketHeadroom;
    private final double targetTraditionalBalance;
    private final double dynamicSequencingBracketRate;

    RothConversionOptimizer(double initTraditional, double initRoth, double initTaxable,
                            double[] otherIncomeByYear, double[] taxableIncomeByYear,
                            int birthYear, int retirementAge, int endAge,
                            int exhaustionBuffer, double conversionBracketRate,
                            double rmdTargetBracketRate, double returnMean,
                            double essentialFloor, double inflationRate,
                            FilingStatus filingStatus, FederalTaxCalculator taxCalculator,
                            String withdrawalOrder,
                            List<ProjectionIncomeSourceInput> incomeSources,
                            RentalLossCalculator rentalLossCalculator,
                            double rmdBracketHeadroom,
                            double dynamicSequencingBracketRate) {
        this.initTraditional = initTraditional;
        this.initRoth = initRoth;
        this.initTaxable = initTaxable;
        this.otherIncomeByYear = otherIncomeByYear;
        this.taxableIncomeByYear = taxableIncomeByYear;
        this.birthYear = birthYear;
        this.retirementAge = retirementAge;
        this.endAge = endAge;
        this.exhaustionBuffer = exhaustionBuffer;
        this.conversionBracketRate = conversionBracketRate;
        this.rmdTargetBracketRate = rmdTargetBracketRate;
        this.returnMean = returnMean;
        this.essentialFloor = essentialFloor;
        this.inflationRate = inflationRate;
        this.filingStatus = filingStatus;
        this.taxCalculator = taxCalculator;
        this.withdrawalOrder = withdrawalOrder;
        this.years = endAge - retirementAge;
        this.rmdStartAge = RmdCalculator.rmdStartAge(birthYear);
        this.rentalLossCalculator = rentalLossCalculator;
        this.rentalSources = incomeSources != null
                ? incomeSources.stream()
                        .filter(s -> s.incomeType() == IncomeSourceType.RENTAL_PROPERTY)
                        .toList()
                : List.of();
        this.rmdBracketHeadroom = rmdBracketHeadroom;
        this.dynamicSequencingBracketRate = dynamicSequencingBracketRate;
        this.targetTraditionalBalance = computeTargetTraditionalBalance();
    }

    /**
     * Computes the target traditional IRA balance at RMD start age such that
     * RMDs stay within the user's target bracket (with headroom for market variability).
     *
     * Formula: targetBalance = availableForRmd × distributionPeriod(rmdStartAge)
     * where availableForRmd = grossBracketCeiling × (1 - headroom) - otherIncomeAtRmdAge
     */
    private double computeTargetTraditionalBalance() {
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
        double otherIncomeAtRmd = rmdYearIndex >= 0 && rmdYearIndex < taxableIncomeByYear.length
                ? taxableIncomeByYear[rmdYearIndex] : 0;
        // Add rental tax adjustment at RMD age (can be negative for losses)
        if (!rentalSources.isEmpty() && rentalLossCalculator != null) {
            var tempSuspended = initSuspendedLosses();
            double rentalAdj = computeRentalAdjustmentForYear(rmdYearIndex, otherIncomeAtRmd, tempSuspended);
            otherIncomeAtRmd += rentalAdj;
        }

        // Bracket ceiling with headroom
        int rmdCalendarYear = birthYear + rmdStartAge;
        double grossCeiling = taxCalculator.computeMaxIncomeForBracket(
                BigDecimal.valueOf(rmdTargetBracketRate), rmdCalendarYear, filingStatus,
                BigDecimal.valueOf(inflationRate)).doubleValue();
        double availableForRmd = grossCeiling * (1 - rmdBracketHeadroom) - otherIncomeAtRmd;

        log.info("Target balance computation: rmdStartAge={}, distributionPeriod={}, " +
                "rmdYearIndex={}, otherIncomeAtRmd={}, rmdCalendarYear={}, " +
                "rmdTargetBracketRate={}, grossCeiling={}, headroom={}, availableForRmd={}, " +
                "result={}",
                rmdStartAge, distributionPeriod, rmdYearIndex, otherIncomeAtRmd,
                rmdCalendarYear, rmdTargetBracketRate, grossCeiling, rmdBracketHeadroom,
                availableForRmd, availableForRmd > 0 ? availableForRmd * distributionPeriod : 0);

        if (availableForRmd <= 0) {
            return 0;
        }
        return availableForRmd * distributionPeriod;
    }

    RothConversionSchedule optimize() {
        var baseline = simulateForFraction(0.0);

        if (initTraditional <= 0) {
            return buildSchedule(baseline, baseline.lifetimeTax, 0.0);
        }

        double bestFraction = findOptimalFraction(baseline);
        var best = simulateForFraction(bestFraction);

        return buildSchedule(best, baseline.lifetimeTax, bestFraction);
    }

    /**
     * Produces the conversion schedule for a specific fraction. Used by the MC optimizer's
     * joint search loop, which scores fractions by sustainable spending rather than lifetime tax.
     */
    RothConversionSchedule scheduleForFraction(double fraction) {
        var result = simulateForFraction(fraction);
        var baseline = simulateForFraction(0.0);
        return buildSchedule(result, baseline.lifetimeTax, fraction);
    }

    /**
     * Returns the baseline (no conversion) schedule.
     */
    RothConversionSchedule baselineSchedule() {
        var baseline = simulateForFraction(0.0);
        return buildSchedule(baseline, baseline.lifetimeTax, 0.0);
    }

    /**
     * Computes rental taxable income adjustment for a single year using
     * RentalLossCalculator with MAGI that includes the conversion amount.
     *
     * For REPS/active properties, MAGI doesn't affect the result — all losses
     * are fully deductible. For passive properties, high MAGI (from conversions)
     * phases out the $25K exception, reducing the deductible loss.
     *
     * @param yearIndex   year within the simulation
     * @param magi        modified adjusted gross income INCLUDING conversion amount
     * @param suspended   per-source suspended loss map (mutated with new values)
     * @return net rental taxable income adjustment (negative = loss offsetting income)
     */
    private double computeRentalAdjustmentForYear(int yearIndex, double magi,
                                                   Map<ProjectionIncomeSourceInput, BigDecimal> suspended) {
        if (rentalSources.isEmpty() || rentalLossCalculator == null) {
            return 0;
        }

        int age = retirementAge + yearIndex;
        int calendarYear = birthYear + age;
        double yearAdjustment = 0;

        for (var source : rentalSources) {
            if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                continue;
            }
            var rentalResult = RentalIncomeHelper.computeForSource(
                    source, yearIndex, calendarYear, magi,
                    suspended.getOrDefault(source, BigDecimal.ZERO),
                    rentalLossCalculator);
            suspended.put(source, rentalResult.newSuspendedLoss());
            yearAdjustment += rentalResult.netTaxableIncome();
        }

        return yearAdjustment;
    }

    private Map<ProjectionIncomeSourceInput, BigDecimal> initSuspendedLosses() {
        var map = new HashMap<ProjectionIncomeSourceInput, BigDecimal>();
        for (var source : rentalSources) {
            map.put(source, BigDecimal.ZERO);
        }
        return map;
    }

    private double findOptimalFraction(SimResult baseline) {
        double bestFraction = 0.0;
        double bestScore = baseline.lifetimeTax;
        boolean bestFeasible = isFeasible(baseline);

        for (int i = 1; i <= GRID_SIZE; i++) {
            double fraction = (double) i / GRID_SIZE;
            var result = simulateForFraction(fraction);
            boolean feasible = isFeasible(result);

            if (isBetterCandidate(result.lifetimeTax, feasible, bestScore, bestFeasible)) {
                bestScore = result.lifetimeTax;
                bestFraction = fraction;
                bestFeasible = feasible;
            }
        }

        double lo = Math.max(0.0, bestFraction - REFINE_HALF_WIDTH);
        double hi = Math.min(1.0, bestFraction + REFINE_HALF_WIDTH);

        for (int iter = 0; iter < REFINE_ITERATIONS; iter++) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;

            var r1 = simulateForFraction(m1);
            var r2 = simulateForFraction(m2);
            boolean f1 = isFeasible(r1);
            boolean f2 = isFeasible(r2);

            double s1 = scoringValue(r1.lifetimeTax, f1);
            double s2 = scoringValue(r2.lifetimeTax, f2);

            if (s1 < s2) {
                hi = m2;
            } else {
                lo = m1;
            }

            if (isBetterCandidate(r1.lifetimeTax, f1, bestScore, bestFeasible)) {
                bestScore = r1.lifetimeTax;
                bestFraction = m1;
                bestFeasible = f1;
            }
            if (isBetterCandidate(r2.lifetimeTax, f2, bestScore, bestFeasible)) {
                bestScore = r2.lifetimeTax;
                bestFraction = m2;
                bestFeasible = f2;
            }
        }

        return bestFraction;
    }

    /**
     * A simulation result is feasible if the traditional balance at RMD start age
     * is at or below the target balance (with 5% tolerance). If there's no target
     * (e.g., already past RMD age), any result is feasible.
     */
    private boolean isFeasible(SimResult result) {
        if (targetTraditionalBalance <= 0) {
            return true;
        }
        int rmdYearIndex = rmdStartAge - retirementAge;
        if (rmdYearIndex < 0 || rmdYearIndex >= result.traditionalBalance.length) {
            return true;
        }
        return result.traditionalBalance[rmdYearIndex] <= targetTraditionalBalance * FEASIBILITY_TOLERANCE;
    }

    private boolean isBetterCandidate(double tax, boolean feasible, double bestTax, boolean bestFeasible) {
        if (feasible && !bestFeasible) return true;
        if (!feasible && bestFeasible) return false;
        return tax < bestTax;
    }

    private double scoringValue(double tax, boolean feasible) {
        return feasible ? tax : tax + 1e12;
    }

    private SimResult simulateForFraction(double conversionFraction) {
        double traditional = initTraditional;
        double roth = initRoth;
        double taxable = initTaxable;
        double priorYearEndTraditional = initTraditional;
        double lifetimeTax = 0;
        int exhaustionAge = endAge;

        var conversionByYear = new double[years];
        var conversionTaxByYear = new double[years];
        var traditionalBal = new double[years];
        var rothBal = new double[years];
        var taxableBal = new double[years];
        var projectedRmd = new double[years];

        // Each simulation run tracks its own suspended loss carryforward
        var suspendedLosses = initSuspendedLosses();

        for (int yearIndex = 0; yearIndex < years; yearIndex++) {
            int age = retirementAge + yearIndex;
            int calendarYear = birthYear + age;
            double baseOtherIncome = yearIndex < otherIncomeByYear.length
                    ? otherIncomeByYear[yearIndex] : 0;

            // Step 1: Apply growth
            traditional *= (1 + returnMean);
            roth *= (1 + returnMean);
            taxable *= (1 + returnMean);

            double conversionAmount = 0;
            double conversionTax = 0;

            // Step 2: Roth conversions (pre-RMD years only)
            if (age < rmdStartAge && traditional > 0 && conversionFraction > 0) {
                var conv = convergeConversionAmount(traditional, taxable, baseOtherIncome,
                        yearIndex, age, calendarYear, conversionFraction, suspendedLosses);
                conversionAmount = conv.conversionAmount();
                conversionTax = conv.conversionTax();

                if (conversionAmount > 0) {
                    traditional -= conversionAmount;
                    roth += conversionAmount;

                    if (age < EARLY_WITHDRAWAL_AGE) {
                        taxable -= conversionTax;
                        if (taxable < 0) taxable = 0;
                    } else {
                        double[] afterDeduct = deductCascade(conversionTax, taxable,
                                traditional, roth);
                        taxable = afterDeduct[0];
                        traditional = afterDeduct[1];
                        roth = afterDeduct[2];
                    }
                }
            } else if (!rentalSources.isEmpty()) {
                // Non-conversion year: still compute rental adjustments for loss
                // carryforward tracking (losses accumulate even when not converting)
                double magi = baseOtherIncome + conversionAmount;
                computeRentalAdjustmentForYear(yearIndex, magi, suspendedLosses);
            }

            // Step 3: RMDs
            double rmdAmount = 0;
            double rmdTax = 0;
            double effectiveOtherIncome = baseOtherIncome
                    + (rentalSources.isEmpty() ? 0
                        : computeRentalAdjustmentForYear(yearIndex,
                            baseOtherIncome + conversionAmount,
                            new HashMap<>(suspendedLosses)));
            if (age >= rmdStartAge && traditional > 0) {
                rmdAmount = RmdCalculator.computeRmd(priorYearEndTraditional, age);
                rmdAmount = Math.min(rmdAmount, traditional);
                traditional -= rmdAmount;
                rmdTax = computeIncrementalTax(rmdAmount, effectiveOtherIncome, calendarYear);

                // Non-conversion RMD year: track rental loss carryforward
                if (conversionAmount == 0 && !rentalSources.isEmpty()) {
                    computeRentalAdjustmentForYear(yearIndex,
                            baseOtherIncome + rmdAmount, suspendedLosses);
                }
            }

            // Step 4: Spending withdrawals
            var withdrawal = processSpendingWithdrawal(taxable, traditional, roth,
                    yearIndex, age, baseOtherIncome, effectiveOtherIncome,
                    rmdAmount, conversionAmount, calendarYear);
            taxable = withdrawal.taxable();
            traditional = withdrawal.traditional();
            roth = withdrawal.roth();

            lifetimeTax += conversionTax + withdrawal.withdrawalTax() + rmdTax;

            priorYearEndTraditional = traditional;
            conversionByYear[yearIndex] = conversionAmount;
            conversionTaxByYear[yearIndex] = conversionTax;
            traditionalBal[yearIndex] = traditional;
            rothBal[yearIndex] = roth;
            taxableBal[yearIndex] = taxable;
            projectedRmd[yearIndex] = rmdAmount;

            if (traditional <= 0 && exhaustionAge == endAge) {
                exhaustionAge = age;
            }
        }

        return new SimResult(conversionByYear, conversionTaxByYear, traditionalBal,
                rothBal, taxableBal, projectedRmd, lifetimeTax, exhaustionAge);
    }

    private record ConvergenceResult(double conversionAmount, double conversionTax) {}

    private record WithdrawalResult(double taxable, double traditional, double roth,
                                    double withdrawalTax) {}

    /**
     * Strategy for computing spending withdrawals from the three account pools.
     * Each implementation encodes a different withdrawal sequencing policy.
     */
    private sealed interface SpendingWithdrawalStrategy {
        WithdrawalResult withdraw(double taxable, double traditional, double roth,
                                  double need, double effectiveOtherIncome,
                                  double rmdAmount, double conversionAmount,
                                  int calendarYear);
    }

    /** Before age 59.5: draw only from taxable to avoid early withdrawal penalties. */
    private record EarlyWithdrawalStrategy() implements SpendingWithdrawalStrategy {
        @Override
        public WithdrawalResult withdraw(double taxable, double traditional, double roth,
                                         double need, double effectiveOtherIncome,
                                         double rmdAmount, double conversionAmount,
                                         int calendarYear) {
            taxable -= need;
            if (taxable < 0) taxable = 0;
            return new WithdrawalResult(taxable, traditional, roth, 0);
        }
    }

    /**
     * Dynamic sequencing: Traditional up to bracket ceiling, then Taxable, then Roth.
     * Maximizes tax-efficient traditional draws within a target bracket.
     */
    private final class DynamicSequencingWithdrawalStrategy implements SpendingWithdrawalStrategy {
        private final double bracketRate;

        DynamicSequencingWithdrawalStrategy(double bracketRate) {
            this.bracketRate = bracketRate;
        }

        @Override
        public WithdrawalResult withdraw(double taxable, double traditional, double roth,
                                         double need, double effectiveOtherIncome,
                                         double rmdAmount, double conversionAmount,
                                         int calendarYear) {
            double remaining = need;
            double withdrawalTax = 0;

            double bracketCeiling = taxCalculator.computeMaxIncomeForBracket(
                    BigDecimal.valueOf(bracketRate), calendarYear, filingStatus,
                    BigDecimal.valueOf(inflationRate)).doubleValue();
            double bracketSpace = Math.max(0, bracketCeiling - effectiveOtherIncome
                    - rmdAmount - conversionAmount);
            double tradDraw = Math.min(bracketSpace, Math.min(traditional, remaining));
            traditional -= tradDraw;
            remaining -= tradDraw;
            withdrawalTax += computeIncrementalTax(tradDraw,
                    effectiveOtherIncome + rmdAmount + conversionAmount, calendarYear);

            double taxDraw = Math.min(remaining, taxable);
            taxable -= taxDraw;
            remaining -= taxDraw;

            double rothDraw = Math.min(remaining, roth);
            roth -= rothDraw;

            return new WithdrawalResult(taxable, traditional, roth, withdrawalTax);
        }
    }

    /**
     * Ordered withdrawal: draws from pools in a configurable comma-separated order
     * (e.g., "taxable,traditional,roth"). Traditional draws incur incremental tax.
     */
    private final class OrderedWithdrawalStrategy implements SpendingWithdrawalStrategy {
        private final String[] pools;

        OrderedWithdrawalStrategy(String[] pools) {
            this.pools = pools;
        }

        @Override
        public WithdrawalResult withdraw(double taxable, double traditional, double roth,
                                         double need, double effectiveOtherIncome,
                                         double rmdAmount, double conversionAmount,
                                         int calendarYear) {
            double remaining = need;
            double withdrawalTax = 0;

            for (var pool : pools) {
                if (remaining <= 0) break;
                switch (pool) {
                    case PoolStrategy.POOL_TAXABLE -> {
                        double draw = Math.min(remaining, taxable);
                        taxable -= draw;
                        remaining -= draw;
                    }
                    case PoolStrategy.POOL_TRADITIONAL -> {
                        double draw = Math.min(remaining, traditional);
                        traditional -= draw;
                        remaining -= draw;
                        withdrawalTax += computeIncrementalTax(draw,
                                effectiveOtherIncome + rmdAmount + conversionAmount,
                                calendarYear);
                    }
                    case PoolStrategy.POOL_ROTH -> {
                        double draw = Math.min(remaining, roth);
                        roth -= draw;
                        remaining -= draw;
                    }
                }
            }

            return new WithdrawalResult(taxable, traditional, roth, withdrawalTax);
        }
    }

    private SpendingWithdrawalStrategy selectWithdrawalStrategy(int age) {
        if (age < EARLY_WITHDRAWAL_AGE) {
            return new EarlyWithdrawalStrategy();
        }
        if (PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING.equals(withdrawalOrder)
                && dynamicSequencingBracketRate > 0) {
            return new DynamicSequencingWithdrawalStrategy(dynamicSequencingBracketRate);
        }
        return new OrderedWithdrawalStrategy(parseWithdrawalOrder());
    }

    private WithdrawalResult processSpendingWithdrawal(
            double taxable, double traditional, double roth,
            int yearIndex, int age, double baseOtherIncome,
            double effectiveOtherIncome, double rmdAmount, double conversionAmount,
            int calendarYear) {

        double inflatedSpending = essentialFloor * Math.pow(1 + inflationRate, yearIndex);
        double netSpendingNeed = Math.max(0, inflatedSpending - baseOtherIncome);

        if (netSpendingNeed <= 0) {
            return new WithdrawalResult(taxable, traditional, roth, 0);
        }

        var strategy = selectWithdrawalStrategy(age);
        return strategy.withdraw(taxable, traditional, roth, netSpendingNeed,
                effectiveOtherIncome, rmdAmount, conversionAmount, calendarYear);
    }

    /**
     * Constrains the conversion amount so the tax on the conversion can be paid
     * from the taxable account without depleting funds needed for essential spending.
     * Only applies before age 59.5 (EARLY_WITHDRAWAL_AGE), when penalty-free
     * traditional/Roth withdrawals are unavailable.
     */
    private double constrainConversionByAffordability(
            double maxConversion, double effectiveIncome, double taxable,
            double baseOtherIncome, int yearIndex, int age, int calendarYear) {
        if (age >= EARLY_WITHDRAWAL_AGE) {
            return maxConversion;
        }
        double tentativeTax = computeIncrementalTax(
                maxConversion, effectiveIncome, calendarYear);
        double inflatedSpending = essentialFloor
                * Math.pow(1 + inflationRate, yearIndex);
        double netSpendingNeed = Math.max(0, inflatedSpending - baseOtherIncome);
        double available = taxable - netSpendingNeed;
        if (available <= 0) {
            return 0;
        } else if (tentativeTax > available) {
            return findMaxAffordableConversion(
                    maxConversion, effectiveIncome, calendarYear, available);
        }
        return maxConversion;
    }

    /**
     * Iterates to find the stable Roth conversion amount where the MAGI used for
     * passive loss rules matches the actual MAGI produced by the conversion.
     * For REPS/active properties this converges in 1 iteration; for passive
     * properties with the $25K exception, 2-3 iterations suffice.
     *
     * @param suspendedLosses mutated in-place with the final iteration's suspended loss state
     */
    private ConvergenceResult convergeConversionAmount(
            double traditional, double taxable, double baseOtherIncome,
            int yearIndex, int age, int calendarYear, double conversionFraction,
            Map<ProjectionIncomeSourceInput, BigDecimal> suspendedLosses) {

        var savedSuspended = new HashMap<>(suspendedLosses);
        double convergedConversion = 0;
        double convergedTax = 0;

        for (int conv = 0; conv < MAGI_CONVERGENCE_ITERATIONS; conv++) {
            // Restore suspended losses to pre-year state for each iteration
            var iterSuspended = new HashMap<>(savedSuspended);

            // Compute rental adjustment using MAGI = base income + conversion estimate
            double estimatedMagi = baseOtherIncome + convergedConversion;
            double rentalAdj = computeRentalAdjustmentForYear(
                    yearIndex, estimatedMagi, iterSuspended);
            double effectiveIncome = baseOtherIncome + rentalAdj;

            // Compute bracket space and conversion amount
            double bracketCeiling = taxCalculator.computeMaxIncomeForBracket(
                    BigDecimal.valueOf(conversionBracketRate), calendarYear, filingStatus,
                    BigDecimal.valueOf(inflationRate)).doubleValue();
            double bracketSpace = Math.max(0, bracketCeiling - effectiveIncome);
            double maxConversion = bracketSpace * conversionFraction;

            // Loss utilization floor: when rental losses create tax-free
            // conversion capacity, convert at least enough to use the losses.
            if (rentalAdj < 0) {
                double freeCapacity = Math.min(Math.abs(rentalAdj), bracketSpace);
                maxConversion = Math.max(maxConversion, freeCapacity);
            }

            // Cap conversions: don't convert below the target balance
            // projected back from RMD age to the current year
            double yearsToRmd = rmdStartAge - age;
            if (yearsToRmd > 0 && targetTraditionalBalance > 0) {
                double traditionalNeededNow = targetTraditionalBalance
                        / Math.pow(1 + returnMean, yearsToRmd);
                double excessTraditional = Math.max(0, traditional - traditionalNeededNow);
                maxConversion = Math.min(maxConversion, excessTraditional);
            }

            maxConversion = constrainConversionByAffordability(
                    maxConversion, effectiveIncome, taxable, baseOtherIncome,
                    yearIndex, age, calendarYear);

            double newConversion = Math.min(maxConversion, traditional);
            double newTax = newConversion > 0
                    ? computeIncrementalTax(newConversion, effectiveIncome, calendarYear)
                    : 0;

            // Check convergence
            if (Math.abs(newConversion - convergedConversion) < CONVERGENCE_THRESHOLD_DOLLARS) {
                suspendedLosses.putAll(iterSuspended);
                convergedConversion = newConversion;
                convergedTax = newTax;
                break;
            }

            convergedConversion = newConversion;
            convergedTax = newTax;

            // On last iteration, commit suspended losses
            if (conv == MAGI_CONVERGENCE_ITERATIONS - 1) {
                suspendedLosses.putAll(iterSuspended);
            }
        }

        return new ConvergenceResult(convergedConversion, convergedTax);
    }

    private double computeIncrementalTax(double additionalIncome, double baseIncome,
                                          int calendarYear) {
        if (additionalIncome <= 0) {
            return 0;
        }
        double taxWithout = taxCalculator.computeTax(
                BigDecimal.valueOf(Math.max(0, baseIncome)), calendarYear, filingStatus)
                .doubleValue();
        double taxWith = taxCalculator.computeTax(
                BigDecimal.valueOf(Math.max(0, baseIncome + additionalIncome)),
                calendarYear, filingStatus).doubleValue();
        return Math.max(0, taxWith - taxWithout);
    }

    private double findMaxAffordableConversion(double maxConversion, double otherIncome,
                                                int calendarYear, double taxBudget) {
        double lo = 0;
        double hi = maxConversion;
        for (int i = 0; i < AFFORDABILITY_BINARY_SEARCH_ITERATIONS; i++) {
            double mid = (lo + hi) / 2.0;
            double tax = computeIncrementalTax(mid, otherIncome, calendarYear);
            if (tax <= taxBudget) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private double[] deductCascade(double amount, double taxable, double traditional,
                                    double roth) {
        double remaining = amount;
        if (remaining > 0 && taxable > 0) {
            double draw = Math.min(remaining, taxable);
            taxable -= draw;
            remaining -= draw;
        }
        if (remaining > 0 && traditional > 0) {
            double draw = Math.min(remaining, traditional);
            traditional -= draw;
            remaining -= draw;
        }
        if (remaining > 0 && roth > 0) {
            double draw = Math.min(remaining, roth);
            roth -= draw;
            remaining -= draw;
        }
        return new double[]{taxable, traditional, roth};
    }

    private String[] parseWithdrawalOrder() {
        if (withdrawalOrder == null || withdrawalOrder.isBlank()) {
            return new String[]{PoolStrategy.POOL_TAXABLE, PoolStrategy.POOL_TRADITIONAL, PoolStrategy.POOL_ROTH};
        }
        return withdrawalOrder.split(",");
    }

    private RothConversionSchedule buildSchedule(SimResult result, double baselineTax,
                                                   double fraction) {
        int exhaustionTarget = endAge - exhaustionBuffer;
        return new RothConversionSchedule(
                result.conversionByYear,
                result.conversionTaxByYear,
                result.traditionalBalance,
                result.rothBalance,
                result.taxableBalance,
                result.projectedRmd,
                result.lifetimeTax,
                baselineTax,
                result.exhaustionAge,
                result.exhaustionAge <= exhaustionTarget,
                fraction,
                targetTraditionalBalance
        );
    }

    private record SimResult(
            double[] conversionByYear,
            double[] conversionTaxByYear,
            double[] traditionalBalance,
            double[] rothBalance,
            double[] taxableBalance,
            double[] projectedRmd,
            double lifetimeTax,
            int exhaustionAge
    ) {}

    static Builder builder() { return new Builder(); }

    static class Builder {
        private double traditional, roth, taxable;
        private double[] otherIncomeByYear, taxableIncomeByYear;
        private int birthYear, retirementAge, endAge, exhaustionBuffer;
        private double conversionBracketRate, rmdTargetBracketRate, returnMean;
        private double essentialFloor, inflationRate, rmdBracketHeadroom;
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

        Builder income(double[] otherIncome, double[] taxableIncome) {
            this.otherIncomeByYear = otherIncome;
            this.taxableIncomeByYear = taxableIncome;
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
                            double inflationRate, int exhaustionBuffer,
                            String withdrawalOrder) {
            this.returnMean = returnMean;
            this.essentialFloor = essentialFloor;
            this.inflationRate = inflationRate;
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
                    essentialFloor, inflationRate, filingStatus, taxCalculator,
                    withdrawalOrder, incomeSources, rentalLossCalculator,
                    rmdBracketHeadroom, dynamicSequencingBracketRate);
        }
    }
}
