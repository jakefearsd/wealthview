package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Finds the optimal Roth conversion schedule that minimizes lifetime tax.
 * Uses a hybrid grid scan + ternary refinement over conversion fraction.
 * Package-private — not a Spring bean; instantiated by the service layer.
 */
class RothConversionOptimizer {

    private static final int GRID_SIZE = 50;
    private static final double REFINE_HALF_WIDTH = 0.05;
    private static final int REFINE_ITERATIONS = 20;
    private static final int EARLY_WITHDRAWAL_AGE = 60; // proxy for 59.5

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
            double conversionFraction
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
    private final List<ProjectionIncomeSourceInput> incomeSources;
    private final RentalLossCalculator rentalLossCalculator;

    /** Pre-computed rental adjustment per year (can be negative for losses). */
    private final double[] rentalAdjustmentByYear;

    RothConversionOptimizer(double initTraditional, double initRoth, double initTaxable,
                            double[] otherIncomeByYear, double[] taxableIncomeByYear,
                            int birthYear, int retirementAge, int endAge,
                            int exhaustionBuffer, double conversionBracketRate,
                            double rmdTargetBracketRate, double returnMean,
                            double essentialFloor, double inflationRate,
                            FilingStatus filingStatus, FederalTaxCalculator taxCalculator,
                            String withdrawalOrder,
                            List<ProjectionIncomeSourceInput> incomeSources,
                            RentalLossCalculator rentalLossCalculator) {
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
        this.incomeSources = incomeSources;
        this.rentalLossCalculator = rentalLossCalculator;
        this.rentalAdjustmentByYear = computeRentalAdjustments();
    }

    RothConversionSchedule optimize() {
        // Baseline: no conversions (fraction = 0)
        var baseline = simulateForFraction(0.0);

        if (initTraditional <= 0) {
            return buildSchedule(baseline, baseline.lifetimeTax, 0.0);
        }

        double bestFraction = findOptimalFraction(baseline);
        var best = simulateForFraction(bestFraction);

        return buildSchedule(best, baseline.lifetimeTax, bestFraction);
    }

    /**
     * Pre-computes per-year rental taxable income adjustments using RentalLossCalculator
     * with passive loss carryforward across years. Each rental property's net taxable
     * contribution (gross - expenses - depreciation) is run through applyLossRules() to
     * handle passive loss limits, $25K exception, and suspended loss carryforward.
     *
     * The returned array values can be negative (losses that offset other income).
     */
    private double[] computeRentalAdjustments() {
        double[] adjustments = new double[years];
        if (incomeSources == null || incomeSources.isEmpty() || rentalLossCalculator == null) {
            return adjustments;
        }

        var rentalSources = incomeSources.stream()
                .filter(s -> "rental_property".equals(s.incomeType()))
                .toList();
        if (rentalSources.isEmpty()) {
            return adjustments;
        }

        // Track suspended losses per property across years
        var suspendedBySource = new java.util.HashMap<ProjectionIncomeSourceInput, BigDecimal>();
        for (var source : rentalSources) {
            suspendedBySource.put(source, BigDecimal.ZERO);
        }

        for (int yearIndex = 0; yearIndex < years; yearIndex++) {
            int age = retirementAge + yearIndex;
            int calendarYear = birthYear + age;
            double baseOtherIncome = yearIndex < otherIncomeByYear.length
                    ? otherIncomeByYear[yearIndex] : 0;
            double yearAdjustment = 0;

            for (var source : rentalSources) {
                if (!isActiveForAge(source, age)) {
                    continue;
                }

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

                // Net rental income: gross - deductible expenses - depreciation
                // (mortgage interest is deductible; mortgage principal is not)
                double mortgageInterest = nullSafe(source.annualMortgageInterest());
                double netRentalIncome = gross - expenses - mortgageInterest - depreciation;

                var priorSuspended = suspendedBySource.get(source);
                var lossResult = rentalLossCalculator.applyLossRules(
                        BigDecimal.valueOf(netRentalIncome),
                        source.taxTreatment(),
                        BigDecimal.ZERO, // otherPassiveIncome
                        BigDecimal.valueOf(Math.max(0, baseOtherIncome)),
                        priorSuspended);

                suspendedBySource.put(source, lossResult.lossSuspended());
                yearAdjustment += lossResult.netTaxableIncome().doubleValue();
            }

            adjustments[yearIndex] = yearAdjustment;
        }

        return adjustments;
    }

    private boolean isActiveForAge(ProjectionIncomeSourceInput source, int age) {
        if (age < source.startAge()) {
            return false;
        }
        return source.endAge() == null || age <= source.endAge();
    }

    private double nullSafe(BigDecimal value) {
        return value != null ? value.doubleValue() : 0;
    }

    private double findOptimalFraction(SimResult baseline) {
        int exhaustionTarget = endAge - exhaustionBuffer;
        double bestFraction = 0.0;
        double bestScore = baseline.lifetimeTax;
        boolean bestFeasible = isFeasible(baseline, exhaustionTarget);

        // Phase 1: Grid scan
        for (int i = 1; i <= GRID_SIZE; i++) {
            double fraction = (double) i / GRID_SIZE;
            var result = simulateForFraction(fraction);
            boolean feasible = isFeasible(result, exhaustionTarget);

            if (isBetterCandidate(result.lifetimeTax, feasible, bestScore, bestFeasible)) {
                bestScore = result.lifetimeTax;
                bestFraction = fraction;
                bestFeasible = feasible;
            }
        }

        // Phase 2: Ternary refinement around the best fraction
        double lo = Math.max(0.0, bestFraction - REFINE_HALF_WIDTH);
        double hi = Math.min(1.0, bestFraction + REFINE_HALF_WIDTH);

        for (int iter = 0; iter < REFINE_ITERATIONS; iter++) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;

            var r1 = simulateForFraction(m1);
            var r2 = simulateForFraction(m2);
            boolean f1 = isFeasible(r1, exhaustionTarget);
            boolean f2 = isFeasible(r2, exhaustionTarget);

            double s1 = scoringValue(r1.lifetimeTax, f1);
            double s2 = scoringValue(r2.lifetimeTax, f2);

            if (s1 < s2) {
                hi = m2;
            } else {
                lo = m1;
            }

            // Check if either midpoint is better than our current best
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

    private boolean isFeasible(SimResult result, int exhaustionTarget) {
        return result.exhaustionAge <= exhaustionTarget;
    }

    private boolean isBetterCandidate(double tax, boolean feasible, double bestTax, boolean bestFeasible) {
        if (feasible && !bestFeasible) {
            return true;
        }
        if (!feasible && bestFeasible) {
            return false;
        }
        return tax < bestTax;
    }

    private double scoringValue(double tax, boolean feasible) {
        // Penalize infeasible solutions heavily so ternary search prefers feasible ones
        return feasible ? tax : tax + 1e12;
    }

    private SimResult simulateForFraction(double conversionFraction) {
        double traditional = initTraditional;
        double roth = initRoth;
        double taxable = initTaxable;
        double priorYearEndTraditional = initTraditional;
        double lifetimeTax = 0;
        int exhaustionAge = endAge; // default: never exhausted

        var conversionByYear = new double[years];
        var conversionTaxByYear = new double[years];
        var traditionalBal = new double[years];
        var rothBal = new double[years];
        var taxableBal = new double[years];
        var projectedRmd = new double[years];

        for (int yearIndex = 0; yearIndex < years; yearIndex++) {
            int age = retirementAge + yearIndex;
            int calendarYear = birthYear + age;
            double otherIncome = yearIndex < otherIncomeByYear.length ? otherIncomeByYear[yearIndex] : 0;

            // Rental-aware taxable income: base other income + rental adjustment
            // The rental adjustment can be negative (losses offsetting income)
            double rentalAdjustment = rentalAdjustmentByYear[yearIndex];
            double effectiveOtherIncome = otherIncome + rentalAdjustment;

            // Step 1: Apply growth
            traditional *= (1 + returnMean);
            roth *= (1 + returnMean);
            taxable *= (1 + returnMean);

            double conversionAmount = 0;
            double conversionTax = 0;

            // Step 2: Roth conversions (pre-RMD years only, and only if traditional > 0)
            if (age < rmdStartAge && traditional > 0 && conversionFraction > 0) {
                double bracketCeiling = taxCalculator.computeMaxIncomeForBracket(
                        BigDecimal.valueOf(conversionBracketRate), calendarYear, filingStatus).doubleValue();
                double bracketSpace = Math.max(0, bracketCeiling - effectiveOtherIncome);
                double maxConversion = bracketSpace * conversionFraction;

                if (age < EARLY_WITHDRAWAL_AGE) {
                    // Tax guard + spending guard: before 59.5, can only pay tax from taxable
                    double tentativeTax = computeIncrementalTax(
                            maxConversion, effectiveOtherIncome, calendarYear);
                    double inflatedSpending = essentialFloor * Math.pow(1 + inflationRate, yearIndex);
                    double netSpendingNeed = Math.max(0, inflatedSpending - otherIncome);

                    // Taxable must cover: spending + conversion tax
                    double available = taxable - netSpendingNeed;
                    if (available <= 0) {
                        maxConversion = 0;
                    } else if (tentativeTax > available) {
                        // Binary search for max conversion that keeps tax within budget
                        maxConversion = findMaxAffordableConversion(
                                maxConversion, effectiveOtherIncome, calendarYear, available);
                    }
                }

                conversionAmount = Math.min(maxConversion, traditional);
                if (conversionAmount > 0) {
                    traditional -= conversionAmount;
                    roth += conversionAmount;
                    conversionTax = computeIncrementalTax(
                            conversionAmount, effectiveOtherIncome, calendarYear);

                    // Deduct conversion tax
                    if (age < EARLY_WITHDRAWAL_AGE) {
                        // Before 59.5: from taxable only
                        taxable -= conversionTax;
                        if (taxable < 0) {
                            taxable = 0;
                        }
                    } else {
                        // At 60+: cascade taxable → traditional → roth
                        double[] afterDeduct = deductCascade(conversionTax, taxable, traditional, roth);
                        taxable = afterDeduct[0];
                        traditional = afterDeduct[1];
                        roth = afterDeduct[2];
                    }
                }
            }

            // Step 3: RMDs
            double rmdAmount = 0;
            double rmdTax = 0;
            if (age >= rmdStartAge && traditional > 0) {
                rmdAmount = RmdCalculator.computeRmd(priorYearEndTraditional, age);
                rmdAmount = Math.min(rmdAmount, traditional);
                traditional -= rmdAmount;
                rmdTax = computeIncrementalTax(rmdAmount, effectiveOtherIncome, calendarYear);
            }

            // Step 4: Spending withdrawals
            double inflatedSpending = essentialFloor * Math.pow(1 + inflationRate, yearIndex);
            double netSpendingNeed = Math.max(0, inflatedSpending - otherIncome);
            double withdrawalTax = 0;

            if (netSpendingNeed > 0) {
                if (age < EARLY_WITHDRAWAL_AGE) {
                    // Before 59.5: draw from taxable only
                    taxable -= netSpendingNeed;
                    if (taxable < 0) {
                        taxable = 0;
                    }
                } else {
                    // At 60+: draw per withdrawal order
                    double remaining = netSpendingNeed;
                    var pools = parseWithdrawalOrder();
                    for (var pool : pools) {
                        if (remaining <= 0) break;
                        switch (pool) {
                            case "taxable" -> {
                                double draw = Math.min(remaining, taxable);
                                taxable -= draw;
                                remaining -= draw;
                            }
                            case "traditional" -> {
                                double draw = Math.min(remaining, traditional);
                                traditional -= draw;
                                remaining -= draw;
                                // Tax on traditional withdrawal
                                withdrawalTax += computeIncrementalTax(
                                        draw,
                                        effectiveOtherIncome + rmdAmount + conversionAmount,
                                        calendarYear);
                            }
                            case "roth" -> {
                                double draw = Math.min(remaining, roth);
                                roth -= draw;
                                remaining -= draw;
                            }
                        }
                    }
                }
            }

            // Step 6: Accumulate lifetime tax
            lifetimeTax += conversionTax + withdrawalTax + rmdTax;

            // Step 7: Track balances and prior year end
            priorYearEndTraditional = traditional;
            conversionByYear[yearIndex] = conversionAmount;
            conversionTaxByYear[yearIndex] = conversionTax;
            traditionalBal[yearIndex] = traditional;
            rothBal[yearIndex] = roth;
            taxableBal[yearIndex] = taxable;
            projectedRmd[yearIndex] = rmdAmount;

            // Step 8: Track exhaustion
            if (traditional <= 0 && exhaustionAge == endAge) {
                exhaustionAge = age;
            }
        }

        return new SimResult(conversionByYear, conversionTaxByYear, traditionalBal,
                rothBal, taxableBal, projectedRmd, lifetimeTax, exhaustionAge);
    }

    private double computeIncrementalTax(double additionalIncome, double baseIncome, int calendarYear) {
        if (additionalIncome <= 0) {
            return 0;
        }
        // Do NOT clamp baseIncome to zero before adding additionalIncome.
        // When baseIncome is negative (rental losses), the loss offsets the
        // additional income: tax(base + additional) computes tax on the NET amount.
        // The tax calculator itself handles negative input by returning $0.
        double taxWithout = taxCalculator.computeTax(
                BigDecimal.valueOf(Math.max(0, baseIncome)), calendarYear, filingStatus).doubleValue();
        double taxWith = taxCalculator.computeTax(
                BigDecimal.valueOf(Math.max(0, baseIncome + additionalIncome)),
                calendarYear, filingStatus).doubleValue();
        return Math.max(0, taxWith - taxWithout);
    }

    private double findMaxAffordableConversion(double maxConversion, double otherIncome,
                                                int calendarYear, double taxBudget) {
        double lo = 0;
        double hi = maxConversion;
        for (int i = 0; i < 30; i++) {
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

    private double[] deductCascade(double amount, double taxable, double traditional, double roth) {
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
            return new String[]{"taxable", "traditional", "roth"};
        }
        return withdrawalOrder.split(",");
    }

    private RothConversionSchedule buildSchedule(SimResult result, double baselineTax, double fraction) {
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
                fraction
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
}
