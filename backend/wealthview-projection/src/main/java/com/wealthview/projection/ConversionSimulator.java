package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Runs the deterministic year-by-year Roth conversion simulation for a fixed
 * conversion fraction.
 *
 * <p>Owns the bracket-fill conversion logic, MAGI convergence loop, RMD draws,
 * withdrawal sequencing strategies, and incremental-tax helpers. Extracted from
 * {@link RothConversionOptimizer} so the optimizer is a thin orchestrator over
 * {@link FractionSearch} and this engine.
 *
 * <p>Pure: no RNG, no shared mutable state across {@link #simulateForFraction}
 * calls. The same inputs always yield the same {@link SimResult}.
 *
 * <p><strong>Frame (audit C4):</strong> every dollar figure this class produces — pool balances,
 * bracket comparisons, the target-traditional-balance projection — is in CONSTANT-REAL terms.
 * {@link #returnMean} grows the three pools ({@code traditional/roth/taxable *= (1 + returnMean)});
 * conversion and RMD-target bracket ceilings are priced via {@code
 * taxCalculator.computeMaxIncomeForBracket(..., ZERO)} — {@code ZERO} inflation, i.e. a FLAT real
 * ceiling with no annual indexing. {@code returnMean} MUST therefore already be a real, fee-adjusted
 * rate before it reaches this class; {@link RothConversionOptimizer.Builder#assumptions} takes it
 * as-is with no further conversion. The one production caller, {@code JointConversionSearch},
 * consumes the rate resolved ONCE per run by {@code OptimizationContextBuilder#resolveReturnMean} —
 * the scenario's fee-adjusted, allocation-blended real return by default, or an explicit (nominal)
 * override Fisher-converted minus the scenario fee. Passing a nominal rate here (e.g. a raw CMA nominal mean) inflates
 * pool growth relative to the flat bracket ceilings, overstates projected RMD pressure, and biases
 * conversion recommendations upward.
 */
final class ConversionSimulator {

    private static final int MAGI_CONVERGENCE_ITERATIONS = 3;
    private static final double CONVERGENCE_THRESHOLD_DOLLARS = 100;
    /** Binary search iterations used to find the maximum affordable conversion amount. */
    private static final int AFFORDABILITY_BINARY_SEARCH_ITERATIONS = 30;

    private final RothConversionConfig config;
    private final double targetTraditionalBalance;

    // Frequently used config values, unpacked for readability.
    private final double returnMean;
    private final double essentialFloor;
    private final double conversionBracketRate;
    private final double dynamicSequencingBracketRate;
    private final int retirementAge;
    private final int endAge;
    private final int birthYear;
    private final int rmdStartAge;
    private final int years;
    private final String withdrawalOrder;
    private final FilingStatus filingStatus;
    private final FederalTaxCalculator taxCalculator;
    private final RentalAdjustmentCalculator rentalAdjustmentCalculator;

    ConversionSimulator(RothConversionConfig config, double targetTraditionalBalance) {
        this.config = config;
        this.targetTraditionalBalance = targetTraditionalBalance;
        this.returnMean = config.returnMean();
        this.essentialFloor = config.essentialFloor();
        this.conversionBracketRate = config.conversionBracketRate();
        this.dynamicSequencingBracketRate = config.dynamicSequencingBracketRate();
        this.retirementAge = config.retirementAge();
        this.endAge = config.endAge();
        this.birthYear = config.birthYear();
        this.rmdStartAge = config.rmdStartAge();
        this.years = config.years();
        this.withdrawalOrder = config.withdrawalOrder();
        this.filingStatus = config.filingStatus();
        this.taxCalculator = config.taxCalculator();
        this.rentalAdjustmentCalculator = config.rentalAdjustmentCalculator();
    }

    SimResult simulateForFraction(double conversionFraction) {
        double[] otherIncomeByYear = config.otherIncomeByYear();
        double traditional = config.initTraditional();
        double roth = config.initRoth();
        double taxable = config.initTaxable();
        double priorYearEndTraditional = config.initTraditional();
        double lifetimeTax = 0;
        int exhaustionAge = endAge;

        var conversionByYear = new double[years];
        var conversionTaxByYear = new double[years];
        var traditionalBal = new double[years];
        var rothBal = new double[years];
        var taxableBal = new double[years];
        var projectedRmd = new double[years];

        // Each simulation run tracks its own suspended loss carryforward
        var suspendedLosses = rentalAdjustmentCalculator.initSuspendedLosses();

        for (int yearIndex = 0; yearIndex < years; yearIndex++) {
            int age = retirementAge + yearIndex;
            int calendarYear = birthYear + age;
            double baseOtherIncome = yearIndex < otherIncomeByYear.length
                    ? otherIncomeByYear[yearIndex] : 0;

            // Step 1: Apply growth
            traditional *= (1 + returnMean);
            roth *= (1 + returnMean);
            taxable *= (1 + returnMean);

            // Step 2: Roth conversions (or rental loss tracking for non-conversion years)
            var convResult = applyConversions(traditional, roth, taxable, age,
                    calendarYear, yearIndex, baseOtherIncome, conversionFraction, suspendedLosses);
            traditional = convResult.traditional();
            roth = convResult.roth();
            taxable = convResult.taxable();

            // Step 3: RMDs
            double effectiveOtherIncome = baseOtherIncome
                    + (rentalAdjustmentCalculator.isEmpty() ? 0
                        : rentalAdjustmentCalculator.adjustmentForYear(yearIndex,
                            baseOtherIncome + convResult.conversionAmount(),
                            new HashMap<>(suspendedLosses)));
            var rmdResult = applyRmds(traditional, taxable, priorYearEndTraditional, age,
                    calendarYear, yearIndex, baseOtherIncome, effectiveOtherIncome,
                    convResult.conversionAmount(), suspendedLosses);
            traditional = rmdResult.traditional();
            taxable = rmdResult.taxable();

            // Step 4: Spending withdrawals
            var withdrawal = processSpendingWithdrawal(taxable, traditional, roth,
                    age, baseOtherIncome, effectiveOtherIncome,
                    rmdResult.rmdAmount(), convResult.conversionAmount(), calendarYear);
            taxable = withdrawal.taxable();
            traditional = withdrawal.traditional();
            roth = withdrawal.roth();

            lifetimeTax += convResult.conversionTax() + withdrawal.withdrawalTax() + rmdResult.rmdTax();

            priorYearEndTraditional = traditional;
            conversionByYear[yearIndex] = convResult.conversionAmount();
            conversionTaxByYear[yearIndex] = convResult.conversionTax();
            traditionalBal[yearIndex] = traditional;
            rothBal[yearIndex] = roth;
            taxableBal[yearIndex] = taxable;
            projectedRmd[yearIndex] = rmdResult.rmdAmount();

            if (traditional <= 0 && exhaustionAge == endAge) {
                exhaustionAge = age;
            }
        }

        return new SimResult(conversionByYear, conversionTaxByYear, traditionalBal,
                rothBal, taxableBal, projectedRmd, lifetimeTax, exhaustionAge);
    }

    private ConversionStepResult applyConversions(
            double traditional, double roth, double taxable,
            int age, int calendarYear, int yearIndex, double baseOtherIncome,
            double conversionFraction,
            Map<ProjectionIncomeSourceInput, BigDecimal> suspendedLosses) {

        double conversionAmount = 0;
        double conversionTax = 0;

        if (age < rmdStartAge && traditional > 0 && conversionFraction > 0) {
            var conv = convergeConversionAmount(traditional, taxable, baseOtherIncome,
                    yearIndex, age, calendarYear, conversionFraction, suspendedLosses);
            conversionAmount = conv.conversionAmount();
            conversionTax = conv.conversionTax();

            if (conversionAmount > 0) {
                traditional -= conversionAmount;
                roth += conversionAmount;

                if (age < RetirementAges.EARLY_WITHDRAWAL_AGE) {
                    taxable -= conversionTax;
                    if (taxable < 0) {
                        taxable = 0;
                    }
                } else {
                    double[] afterDeduct = PoolTaxCascade.deduct(conversionTax, taxable,
                            traditional, roth);
                    taxable = afterDeduct[0];
                    traditional = afterDeduct[1];
                    roth = afterDeduct[2];
                }
            }
        } else if (!rentalAdjustmentCalculator.isEmpty()) {
            // Non-conversion year: still compute rental adjustments for loss
            // carryforward tracking (losses accumulate even when not converting)
            double magi = baseOtherIncome + conversionAmount;
            rentalAdjustmentCalculator.adjustmentForYear(yearIndex, magi, suspendedLosses);
        }

        return new ConversionStepResult(traditional, roth, taxable, conversionAmount, conversionTax);
    }

    private RmdStepResult applyRmds(
            double traditional, double taxable, double priorYearEndTraditional,
            int age, int calendarYear, int yearIndex,
            double baseOtherIncome, double effectiveOtherIncome,
            double conversionAmount,
            Map<ProjectionIncomeSourceInput, BigDecimal> suspendedLosses) {

        double rmdAmount = 0;
        double rmdTax = 0;

        if (age >= rmdStartAge && traditional > 0) {
            rmdAmount = RmdCalculator.computeRmd(priorYearEndTraditional, age);
            rmdAmount = Math.min(rmdAmount, traditional);
            traditional -= rmdAmount;
            rmdTax = computeIncrementalTax(rmdAmount, effectiveOtherIncome, calendarYear);

            // A forced RMD doesn't vanish: the after-tax remainder is proceeds the account owner
            // actually receives and (absent an offsetting spending need) holds/reinvests in the
            // taxable account. Crediting it here keeps traditional -= RMD, RMD == tax + credited
            // remainder, and stops the without-conversion arm from being artificially penalized by
            // proceeds that disappeared from the simulation (audit C4).
            taxable += Math.max(0, rmdAmount - rmdTax);

            // Non-conversion RMD year: track rental loss carryforward
            if (conversionAmount == 0 && !rentalAdjustmentCalculator.isEmpty()) {
                rentalAdjustmentCalculator.adjustmentForYear(yearIndex,
                        baseOtherIncome + rmdAmount, suspendedLosses);
            }
        }

        return new RmdStepResult(traditional, taxable, rmdAmount, rmdTax);
    }

    private record ConvergenceResult(double conversionAmount, double conversionTax) {}

    private record ConversionStepResult(double traditional, double roth, double taxable,
                                        double conversionAmount, double conversionTax) {}

    private record RmdStepResult(double traditional, double taxable, double rmdAmount, double rmdTax) {}

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
            if (taxable < 0) {
                taxable = 0;
            }
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
                    BigDecimal.ZERO).doubleValue();
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

        OrderedWithdrawalStrategy(String... pools) {
            this.pools = pools.clone();
        }

        @Override
        public WithdrawalResult withdraw(double taxable, double traditional, double roth,
                                         double need, double effectiveOtherIncome,
                                         double rmdAmount, double conversionAmount,
                                         int calendarYear) {
            double remaining = need;
            double withdrawalTax = 0;

            for (var pool : pools) {
                if (remaining <= 0) {
                    break;
                }
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
                    default -> { /* unrecognized pool type — skip */ }
                }
            }

            return new WithdrawalResult(taxable, traditional, roth, withdrawalTax);
        }
    }

    private SpendingWithdrawalStrategy selectWithdrawalStrategy(int age) {
        if (age < RetirementAges.EARLY_WITHDRAWAL_AGE) {
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
            int age, double baseOtherIncome,
            double effectiveOtherIncome, double rmdAmount, double conversionAmount,
            int calendarYear) {

        double inflatedSpending = essentialFloor;
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
     * Only applies before age 59.5 (RetirementAges.EARLY_WITHDRAWAL_AGE), when penalty-free
     * traditional/Roth withdrawals are unavailable.
     */
    private double constrainConversionByAffordability(
            double maxConversion, double effectiveIncome, double taxable,
            double baseOtherIncome, int age, int calendarYear) {
        if (age >= RetirementAges.EARLY_WITHDRAWAL_AGE) {
            return maxConversion;
        }
        double tentativeTax = computeIncrementalTax(
                maxConversion, effectiveIncome, calendarYear);
        double inflatedSpending = essentialFloor;
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
            double rentalAdj = rentalAdjustmentCalculator.adjustmentForYear(
                    yearIndex, estimatedMagi, iterSuspended);
            double effectiveIncome = baseOtherIncome + rentalAdj;

            // Compute bracket space and conversion amount
            double bracketCeiling = taxCalculator.computeMaxIncomeForBracket(
                    BigDecimal.valueOf(conversionBracketRate), calendarYear, filingStatus,
                    BigDecimal.ZERO).doubleValue();
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
                        / CompoundGrowth.factor(returnMean, (int) yearsToRmd);
                double excessTraditional = Math.max(0, traditional - traditionalNeededNow);
                maxConversion = Math.min(maxConversion, excessTraditional);
            }

            maxConversion = constrainConversionByAffordability(
                    maxConversion, effectiveIncome, taxable, baseOtherIncome,
                    age, calendarYear);

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

    private String[] parseWithdrawalOrder() {
        if (withdrawalOrder == null || withdrawalOrder.isBlank()) {
            return new String[]{PoolStrategy.POOL_TAXABLE, PoolStrategy.POOL_TRADITIONAL, PoolStrategy.POOL_ROTH};
        }
        return withdrawalOrder.split(",");
    }
}
