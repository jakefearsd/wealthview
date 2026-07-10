package com.wealthview.projection;

import com.wealthview.core.projection.strategy.WithdrawalOrder;

/**
 * Runs single-path Monte Carlo trials for the spending optimizer: year-by-year
 * portfolio evolution with growth, Roth conversions, withdrawals, withdrawal tax,
 * income surplus, and cash-reserve handling.
 *
 * <p>Extracted from {@code MonteCarloSpendingOptimizer} during the Phase 3 decomposition.
 * The numeric logic is unchanged — this class only isolates the per-trial simulation
 * concern from the optimizer's orchestration, search, and aggregation responsibilities.
 *
 * <p>This class is stateless; a single instance is shared across all trials. Determinism
 * is the caller's responsibility — the simulator consumes pre-computed per-pool real-return
 * sequences (on the {@link SimulationConfig}) and never draws random numbers itself.
 */
final class TrialSimulator {

    /** Fraction of equity portfolio used each year to replenish the cash reserve bucket. */
    private static final double CASH_REPLENISHMENT_RATE = 0.10;

    /** Per-trial simulation result. */
    record TrialResult(
            double finalBalance,
            double minBalance,
            double[] yearBalances,       // null when trackYearBalances is false
            boolean traditionalExhausted,
            boolean success
    ) {}

    /**
     * Configuration for one trial. The run-invariant fields (balances, order, tax/conversion
     * arrays, cash config) are shared across trials; the three per-pool real return sequences
     * ({@code taxableReturns}/{@code traditionalReturns}/{@code rothReturns}) vary per trial —
     * each pool grows at its own allocation/override-driven sequence.
     */
    record SimulationConfig(
            double initTaxable, double initTraditional, double initRoth,
            String withdrawalOrder, double[] marginalRateByYear,
            double[] conversionByYear, double[] conversionTaxByYear,
            int retirementAge,
            double[] dsBracketCeilingByYear,
            int cashReserveYears, double cashReturnRate,
            boolean trackYearBalances,
            double[] taxableReturns, double[] traditionalReturns, double[] rothReturns
    ) {}

    /**
     * Simulates a single MC trial: year-by-year portfolio evolution with growth,
     * Roth conversions, withdrawals, tax, surplus, and floor enforcement.
     *
     * <p>When {@code config.marginalRateByYear()} is non-null, withdrawal tax is
     * estimated and deducted from pools (used by sustainability checks). When null,
     * no withdrawal tax is applied (used by the final response simulation).
     *
     * <p>When {@code config.trackYearBalances()} is true, per-year total balances are
     * recorded in the returned {@link TrialResult#yearBalances()}.
     */
    // NPathComplexity: the year-by-year simulation loop branches on independent per-year
    // conditions (tax mode, cash reserve, tracking). The path count is multiplicative but each
    // branch is a small guarded block, so the method is far simpler than its NPath number.
    @SuppressWarnings("PMD.NPathComplexity")
    TrialResult simulateTrial(
            double[] income, double[] surplusTax,
            double[] floors, double[] discretionary,
            int years, SimulationConfig config) {

        boolean hasConversions = config.conversionByYear() != null;
        boolean hasPools = config.marginalRateByYear() != null;
        double[] taxableReturns = config.taxableReturns();
        double[] traditionalReturns = config.traditionalReturns();
        double[] rothReturns = config.rothReturns();

        // pools[0] = taxable, pools[1] = traditional, pools[2] = roth
        double[] pools = { config.initTaxable(), config.initTraditional(), config.initRoth() };
        // Resolve the withdrawal order once per trial (constant for the run) so the
        // per-year splitWithdrawal calls do no String.equals work.
        WithdrawalOrder order = WithdrawalOrder.fromString(config.withdrawalOrder());

        double cashBalance = 0;
        if (config.cashReserveYears() > 0) {
            double annualSpending = floors[0] + discretionary[0];
            cashBalance = annualSpending * config.cashReserveYears();
            double cashFromTaxable = Math.min(cashBalance, pools[0]);
            pools[0] -= cashFromTaxable;
            double remaining = cashBalance - cashFromTaxable;
            if (remaining > 0) {
                double fromTrad = Math.min(remaining, pools[1]);
                pools[1] -= fromTrad;
                remaining -= fromTrad;
                pools[2] -= remaining;
                pools[2] = Math.max(0, pools[2]);
            }
        }

        double minBalance = pools[0] + pools[1] + pools[2] + cashBalance;
        double[] yearBalances = config.trackYearBalances() ? new double[years] : null;
        boolean essentialFloorMet = true;

        for (int y = 0; y < years; y++) {
            double taxableReturn = taxableReturns[y];
            double traditionalReturn = traditionalReturns[y];
            double rothReturn = rothReturns[y];

            // Cash-reserve down-year logic keys on the balance-weighted portfolio real return
            // across the three pools (pre-growth balances). Now that pools grow at distinct rates
            // there is no single "the return" — this weighted figure preserves the original bucket
            // behavior: draw from cash when the portfolio as a whole had a down year.
            double preGrowthTotal = pools[0] + pools[1] + pools[2];
            double portfolioReturn = preGrowthTotal > 0
                    ? (pools[0] * taxableReturn + pools[1] * traditionalReturn + pools[2] * rothReturn)
                            / preGrowthTotal
                    : 0.0;

            pools[0] *= (1 + taxableReturn);
            pools[1] *= (1 + traditionalReturn);
            pools[2] *= (1 + rothReturn);
            cashBalance *= (1 + config.cashReturnRate());

            int age = config.retirementAge() + y;

            // Roth conversion execution
            applyTrialConversion(pools, config.conversionByYear(), config.conversionTaxByYear(), y, age);

            double spending = floors[y] + discretionary[y];
            double withdrawal = Math.max(0, spending - income[y]);

            // Split withdrawal across pools (59.5 rule: taxable only before age 60)
            boolean preAge595 = hasConversions && age < RetirementAges.EARLY_WITHDRAWAL_AGE;
            double dsCeiling = config.dsBracketCeilingByYear() != null
                    ? config.dsBracketCeilingByYear()[y] : 0;
            double dsConvAmt = config.conversionByYear() != null
                    ? config.conversionByYear()[y] : 0;
            var drawn = splitWithdrawal(pools[0], pools[1], pools[2],
                    withdrawal, order, preAge595,
                    dsCeiling, income[y], dsConvAmt, 0);

            // Estimate tax on traditional withdrawal using pre-computed marginal rate
            double withdrawalTax = 0;
            if (hasPools && drawn.traditional() > 0) {
                withdrawalTax = estimateWithdrawalTax(
                        drawn.traditional(), config.marginalRateByYear()[y]);
            }

            // Withdraw from pools + handle cash reserve
            double cashBeforeWithdrawals = cashBalance;
            cashBalance = applyTrialWithdrawals(pools, cashBalance, drawn, withdrawalTax,
                    withdrawal, spending, hasPools, config.cashReserveYears(), portfolioReturn);
            double cashDrawn = Math.max(0, cashBeforeWithdrawals - cashBalance);
            double resourcesForSpending = income[y] + drawn.total() + cashDrawn;
            if (resourcesForSpending < floors[y] - 1e-6) {
                essentialFloorMet = false;
            }

            // Surplus: income exceeds spending — deposit after-tax surplus to taxable
            if (income[y] > spending) {
                double grossSurplus = income[y] - spending;
                pools[0] += Math.max(0, grossSurplus - surplusTax[y]);
            }

            pools[0] = Math.max(0, pools[0]);
            pools[1] = Math.max(0, pools[1]);
            pools[2] = Math.max(0, pools[2]);
            cashBalance = Math.max(0, cashBalance);

            double totalBalance = pools[0] + pools[1] + pools[2] + cashBalance;
            minBalance = Math.min(minBalance, totalBalance);
            if (yearBalances != null) {
                yearBalances[y] = totalBalance;
            }
        }

        double finalBalance = Math.max(0, pools[0] + pools[1] + pools[2] + cashBalance);
        boolean traditionalExhausted = config.conversionByYear() != null && pools[1] <= 0;

        return new TrialResult(finalBalance, minBalance, yearBalances, traditionalExhausted, essentialFloorMet);
    }

    /**
     * Deducts a tax amount from pools in order: taxable, traditional, roth.
     * Mutates the pools array in place via the shared {@link PoolTaxCascade}.
     */
    // UseVarargs: `pools` is a fixed-length [taxable, traditional, roth] index array mutated in
    // place, not a variable argument list — varargs would obscure the positional contract.
    @SuppressWarnings("PMD.UseVarargs")
    private static void deductTaxFromPools(double tax, double[] pools) {
        double[] after = PoolTaxCascade.deduct(tax, pools[0], pools[1], pools[2]);
        pools[0] = after[0];
        pools[1] = after[1];
        pools[2] = after[2];
    }

    /**
     * Executes a Roth conversion for this trial year: transfers from traditional to roth,
     * then deducts conversion tax from pools.
     */
    private static void applyTrialConversion(double[] pools, double[] conversionByYear,
                                              double[] conversionTaxByYear, int y, int age) {
        if (conversionByYear == null || conversionByYear[y] <= 0 || pools[1] <= 0) {
            return;
        }
        double actualConv = Math.min(conversionByYear[y], pools[1]);
        pools[1] -= actualConv;
        pools[2] += actualConv;
        double actualTax = (actualConv < conversionByYear[y])
                ? conversionTaxByYear[y] * (actualConv / conversionByYear[y])
                : conversionTaxByYear[y];
        if (age < RetirementAges.EARLY_WITHDRAWAL_AGE) {
            pools[0] -= Math.min(actualTax, Math.max(0, pools[0]));
        } else {
            deductTaxFromPools(actualTax, pools);
        }
    }

    /**
     * Deducts withdrawals and tax from pools, handling cash reserve logic.
     * Returns updated cash balance.
     */
    private static double applyTrialWithdrawals(double[] pools, double cashBalance,
                                                 PoolWithdrawal drawn, double withdrawalTax,
                                                 double withdrawal, double spending,
                                                 boolean hasPools, int cashReserveYears,
                                                 double portfolioReturn) {
        if (cashReserveYears > 0) {
            if (portfolioReturn < 0) {
                if (hasPools) {
                    // sustainability path: tax-aware cash reserve draw
                    double totalDraw = withdrawal + withdrawalTax;
                    double cashDraw = Math.min(totalDraw, cashBalance);
                    double equityDraw = totalDraw - cashDraw;
                    double drawnTotal = drawn.total();
                    if (drawnTotal > 0 && equityDraw > 0) {
                        double scale = equityDraw / Math.max(drawnTotal, equityDraw);
                        pools[0] -= drawn.taxable() * scale
                                + withdrawalTax * Math.min(pools[0], withdrawalTax)
                                / Math.max(1, pools[0] + pools[1] + pools[2]);
                    } else {
                        pools[0] -= drawn.taxable();
                        pools[1] -= drawn.traditional();
                        pools[2] -= drawn.roth();
                    }
                    return cashBalance - cashDraw;
                } else {
                    // final-response path: simple cash reserve draw (no withdrawal tax)
                    double cashDraw = Math.min(withdrawal, cashBalance);
                    double equityDraw = withdrawal - cashDraw;
                    double drawnTotal = drawn.total();
                    if (drawnTotal > 0 && equityDraw > 0) {
                        double scale = equityDraw / Math.max(drawnTotal, equityDraw);
                        pools[0] -= drawn.taxable() * scale;
                        pools[1] -= drawn.traditional() * scale;
                        pools[2] -= drawn.roth() * scale;
                    }
                    return cashBalance - cashDraw;
                }
            } else {
                // Up market: normal withdrawal + replenish cash
                pools[0] -= drawn.taxable();
                pools[1] -= drawn.traditional();
                pools[2] -= drawn.roth();
                if (hasPools) {
                    deductTaxFromPools(withdrawalTax, pools);
                }
                double targetCash = spending * cashReserveYears;
                double replenishment = Math.min(
                        Math.max(0, targetCash - cashBalance),
                        Math.max(0, pools[0] + pools[1] + pools[2])
                                * CASH_REPLENISHMENT_RATE);
                pools[0] -= replenishment;
                if (pools[0] < 0) {
                    pools[1] += pools[0];
                    pools[0] = 0;
                }
                return cashBalance + replenishment;
            }
        } else {
            // No cash reserve
            pools[0] -= drawn.taxable();
            pools[1] -= drawn.traditional();
            pools[2] -= drawn.roth();
            if (hasPools) {
                deductTaxFromPools(withdrawalTax, pools);
            }
            return cashBalance;
        }
    }

    /**
     * Split a withdrawal need across three pools using the specified ordering.
     * Returns a {@link PoolWithdrawal} describing how much comes from each pool.
     * When preAge595 is true, only the taxable pool is available (59.5 early withdrawal rule).
     */
    static PoolWithdrawal splitWithdrawal(double taxable, double traditional, double roth,
                                           double need, String order, boolean preAge595,
                                           double dsBracketCeiling, double otherIncome,
                                           double conversionAmount, double rmdAmount) {
        // Resolve the string order to the enum once for non-hot-path callers (the
        // facade + tests). The hot trial loop resolves it once per trial instead
        // (see simulateTrial) and calls the enum overload directly.
        return splitWithdrawal(taxable, traditional, roth, need, WithdrawalOrder.fromString(order),
                preAge595, dsBracketCeiling, otherIncome, conversionAmount, rmdAmount);
    }

    static PoolWithdrawal splitWithdrawal(double taxable, double traditional, double roth,
                                           double need, WithdrawalOrder order, boolean preAge595,
                                           double dsBracketCeiling, double otherIncome,
                                           double conversionAmount, double rmdAmount) {
        if (need <= 0) {
            return new PoolWithdrawal(0, 0, 0);
        }
        if (preAge595) {
            double drawn = Math.min(need, Math.max(0, taxable));
            return new PoolWithdrawal(drawn, 0, 0);
        }

        // Scalar greedy draw in priority order — no per-call array allocation. Each
        // branch draws pools in its order, capping at the available balance, and
        // returns the result mapped to (taxable, traditional, roth).
        switch (order) {
            case DYNAMIC_SEQUENCING: {
                // Traditional first up to bracket space, then taxable, then Roth.
                double bracketSpace = Math.max(0,
                        dsBracketCeiling - otherIncome - conversionAmount - rmdAmount);
                double fromTrad = Math.min(bracketSpace, Math.min(Math.max(0, traditional), need));
                double remaining = need - fromTrad;
                double fromTax = Math.min(remaining, Math.max(0, taxable));
                remaining -= fromTax;
                double fromRoth = Math.min(remaining, Math.max(0, roth));
                return new PoolWithdrawal(fromTax, fromTrad, fromRoth);
            }
            case TRADITIONAL_FIRST: {
                double fromTrad = Math.min(need, Math.max(0, traditional));
                double remaining = need - fromTrad;
                double fromTax = Math.min(remaining, Math.max(0, taxable));
                remaining -= fromTax;
                double fromRoth = Math.min(remaining, Math.max(0, roth));
                return new PoolWithdrawal(fromTax, fromTrad, fromRoth);
            }
            case ROTH_FIRST: {
                double fromRoth = Math.min(need, Math.max(0, roth));
                double remaining = need - fromRoth;
                double fromTax = Math.min(remaining, Math.max(0, taxable));
                remaining -= fromTax;
                double fromTrad = Math.min(remaining, Math.max(0, traditional));
                return new PoolWithdrawal(fromTax, fromTrad, fromRoth);
            }
            default: { // TAXABLE_FIRST (and PRO_RATA, which the MC path treats as taxable-first)
                double fromTax = Math.min(need, Math.max(0, taxable));
                double remaining = need - fromTax;
                double fromTrad = Math.min(remaining, Math.max(0, traditional));
                remaining -= fromTrad;
                double fromRoth = Math.min(remaining, Math.max(0, roth));
                return new PoolWithdrawal(fromTax, fromTrad, fromRoth);
            }
        }
    }

    /**
     * Estimates marginal tax on a traditional withdrawal using the pre-computed
     * marginal rate for the year. This avoids calling computeTax() (which uses
     * BigDecimal) inside the hot MC trial loop — with 10,000 trials × 28 years
     * × 40 binary search iterations, the BigDecimal overhead is prohibitive.
     */
    private static double estimateWithdrawalTax(double traditionalWithdrawal,
                                                  double marginalRate) {
        if (traditionalWithdrawal <= 0) {
            return 0;
        }
        return traditionalWithdrawal * marginalRate;
    }
}
