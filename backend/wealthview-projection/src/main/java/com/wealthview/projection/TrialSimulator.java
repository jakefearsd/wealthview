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

    /** Defensive cap on the audit-C2 gross-up rate: a real marginal rate cannot reach 100% (which
     * would make the T*m/(1-m) closed form diverge), and this model's brackets never approach it --
     * 50% is a generous ceiling that only ever bites on a corrupt/out-of-range input. */
    private static final double GROSS_UP_RATE_CAP = 0.50;

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
            String withdrawalOrder,
            OrdinaryTaxTable[] ordinaryTaxTableByYear, double[] ordinaryBaseIncomeByYear,
            double[] conversionByYear, double[] conversionTaxByYear,
            int retirementAge,
            double[] dsBracketCeilingByYear,
            int cashReserveYears, double cashReturnRate,
            boolean trackYearBalances,
            double[] taxableReturns, double[] traditionalReturns, double[] rothReturns,
            int rmdStartAge,
            double initTaxableBasis, LtcgTaxTable[] ltcgTaxTableByYear, double dividendYield
    ) {}

    /**
     * Simulates a single MC trial: year-by-year portfolio evolution with growth,
     * Roth conversions, withdrawals, tax, surplus, and floor enforcement.
     *
     * <p>When {@code config.ordinaryTaxTableByYear()} is non-null, withdrawal tax is priced EXACTLY
     * (audit C5) against that year's {@link OrdinaryTaxTable} -- {@code taxAt(base + draw) -
     * taxAt(base)} -- and deducted from pools (used by sustainability checks). When null, no
     * withdrawal tax is applied (used by the final response simulation).
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
        boolean hasPools = config.ordinaryTaxTableByYear() != null;
        double[] taxableReturns = config.taxableReturns();
        double[] traditionalReturns = config.traditionalReturns();
        double[] rothReturns = config.rothReturns();

        // pools[0] = taxable, pools[1] = traditional, pools[2] = roth
        double[] pools = { config.initTaxable(), config.initTraditional(), config.initRoth() };
        // Basis-aware taxable pool (Task 6): a parallel FIFO-lot view kept in lock-step with the
        // pools[0] scalar (invariant lots.totalValue() == pools[0]). The scalar remains the value
        // source-of-truth for the intricate cash-reserve/min/final arithmetic; the lots supply only
        // the dividend split and the realized FIFO gain that drive the annual LTCG tax outflow. The
        // initial lot carries the taxable pool's embedded (unrealized) gain: value − basis.
        TaxableLots lots = new TaxableLots();
        lots.addLot(config.initTaxableBasis(), config.initTaxable());
        // Resolve the withdrawal order once per trial (constant for the run) so the
        // per-year splitWithdrawal calls do no String.equals work.
        WithdrawalOrder order = WithdrawalOrder.fromString(config.withdrawalOrder());

        double cashBalance = seedCashReserve(pools, lots, floors, discretionary, config);

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

            // Prior-year-end traditional balance — the IRS RMD basis for this year, snapshotted
            // before this year's growth is applied.
            double pools1PreGrowth = pools[1];

            double dividendIncome = growTaxableWithDividend(
                    pools, lots, taxableReturn, config.dividendYield());
            pools[1] *= (1 + traditionalReturn);
            pools[2] *= (1 + rothReturn);
            cashBalance *= (1 + config.cashReturnRate());

            int age = config.retirementAge() + y;
            double rmd = computeYearRmd(pools1PreGrowth, age, config.rmdStartAge());

            // This year's exact ordinary tax table (audit C5) and the base ordinary income the
            // year's income events stack on -- replaces the old flat $50k-chord marginal rate.
            // STACKING ORDER (C5 review fix): the year's ordinary income accumulates through the
            // loop body in execution order -- base income, then the Roth conversion, then the
            // traditional spending draw, then the forced RMD excess -- and every pricing call
            // stacks its own amount on top of everything realized BEFORE it, so the year's summed
            // ordinary tax telescopes to exactly taxAt(base+conv+draw+excess) - taxAt(base)
            // (pinned by TrialSimulatorReturnTest's composition-identity test). `table` is null
            // when hasPools is false (no tax modeling this trial), which naturally makes every
            // pricing and gross-up call a no-op.
            OrdinaryTaxTable table = hasPools ? config.ordinaryTaxTableByYear()[y] : null;
            double base = hasPools ? config.ordinaryBaseIncomeByYear()[y] : 0.0;

            // Roth conversion execution -- the FIRST ordinary income stacked on the year's base.
            // actualConv is the traditional-balance-CAPPED amount actually converted (0 when no
            // conversion runs); every later ordinary pricing call stacks on it.
            double actualConv = applyTrialConversion(pools, lots, config.conversionByYear(),
                    config.conversionTaxByYear(), y, age, table, base);

            double spending = floors[y] + discretionary[y];
            // A4: tax on this year's base (outside) income is a real obligation every year, not
            // just when income exceeds spending -- fund it from any surplus first; the unfunded
            // remainder drains straight from the pools via the shared tax cascade below
            // (deductTaxFromPoolsGrossedUp), deliberately NOT folded into the spending withdrawal: it
            // must neither draw from the cash reserve nor generate marginal withdrawal tax on the
            // SPENDING side (that would double-count -- this is a separate, already-computed bill).
            // The funding draw itself IS grossed up when it touches traditional (audit C2) exactly
            // like the deterministic engine's extraPoolFundedTax now is. surplusTax[y] is the
            // precomputed full-year tax on this year's taxable base income -- see
            // OptimizationContextBuilder#computeSurplusTax.
            double grossSurplus = income[y] - spending;
            double baseIncomeTax = surplusTax[y];
            double fundedFromSurplus = Math.min(Math.max(0, grossSurplus), baseIncomeTax);
            double unfundedBaseTax = baseIncomeTax - fundedFromSurplus;
            double withdrawal = Math.max(0, spending - income[y]);

            // Split withdrawal across pools (59.5 rule: taxable only before age 60)
            boolean preAge595 = hasConversions && age < RetirementAges.EARLY_WITHDRAWAL_AGE;
            double dsCeiling = config.dsBracketCeilingByYear() != null
                    ? config.dsBracketCeilingByYear()[y] : 0;
            double dsConvAmt = config.conversionByYear() != null
                    ? config.conversionByYear()[y] : 0;
            var drawn = splitWithdrawal(pools[0], pools[1], pools[2],
                    withdrawal, order, preAge595,
                    dsCeiling, income[y], dsConvAmt, rmd);

            // EXACT incremental tax on the traditional withdrawal (audit C5): the draw stacks on
            // base + this year's ACTUAL conversion income -- taxAt(base + actualConv + draw) -
            // taxAt(base + actualConv) -- correctly pricing any bracket crossing within the draw
            // AND the bracket position the conversion already pushed the year into (C5 review fix:
            // pre-fix the conversion's income was invisible here, pricing the draw in the base's
            // own, lower bracket).
            double withdrawalTax = 0;
            if (hasPools && drawn.traditional() > 0) {
                withdrawalTax = table.incrementalTax(base + actualConv, drawn.traditional());
            }

            // Withdraw from pools + handle cash reserve. The taxable spending sale realizes a FIFO
            // long-term gain (accumulated into realizedGainOut[0]); secondary taxable sales — the
            // withdrawal-tax payment and cash-reserve replenishment — sell FIFO to keep the lots in
            // sync but their gain is deliberately discarded (untaxed), matching the deterministic
            // MultiPool's second-order exclusion.
            double cashBeforeWithdrawals = cashBalance;
            double[] realizedGainOut = {0.0};
            double[] traditionalDrawnOut = {0.0};
            cashBalance = applyTrialWithdrawals(pools, lots, realizedGainOut, traditionalDrawnOut,
                    cashBalance, drawn, withdrawalTax, withdrawal, spending, hasPools,
                    config.cashReserveYears(), portfolioReturn, table, base + actualConv);
            double cashDrawn = Math.max(0, cashBeforeWithdrawals - cashBalance);

            // If the RMD exceeds what the spend withdrawal ACTUALLY drew from traditional this
            // year (traditionalDrawnOut[0] -- not the raw pool-split target drawn.traditional(),
            // since a cash-reserve down year may have funded spending from cash instead, leaving
            // traditional untouched), force the excess out physically -- it's a real,
            // legally-required distribution even when the retiree doesn't need the cash. The
            // after-tax remainder is reinvested to taxable; the excess's own tax is priced EXACTLY
            // on top of the full prior ordinary stack (base + conversion + spending draw), C5
            // review fix -- this REPLACES the earlier single-point-rate approximation. (Still not
            // routed through the C2 gross-up: the tax leaks directly, it is not a
            // deductTaxFromPools drain.)
            // ORDERING NOTE (pre-existing; T10 review): this reads pools[1] AFTER the withdrawal-tax
            // drain inside applyTrialWithdrawals above -- which, post-C2, removes MORE from
            // traditional than before (the gross-up enlarges the drain). In the near-depletion edge
            // where that drain leaves pools[1] below the remaining RMD excess, the `extra =
            // min(excess, pools[1])` cap inside forceRmdExcess under-forces the RMD. Pre-existing
            // behavior, merely enlarged by C2; reordering the RMD force-out ahead of the tax drain
            // is follow-up-ticket material, not a silent behavior change to make here.
            double rmdExcess = forceRmdExcess(pools, lots, rmd, traditionalDrawnOut[0], table,
                    base + actualConv + traditionalDrawnOut[0]);

            // The base-income-tax deduction is NOT part of drawn/cashDrawn (it drains via
            // deductTaxFromPoolsGrossedUp below, like withdrawalTax), so this metric already measures
            // spending resources only -- identical to its pre-A4 shape.
            double resourcesForSpending = income[y] + drawn.total() + cashDrawn;
            if (resourcesForSpending < floors[y] - 1e-6) {
                essentialFloorMet = false;
            }

            // Full ordinary stack realized this year -- the point every remaining tax bill's
            // funding draw (and the LTCG bracket floor) stacks on.
            double ordinaryStack = base + actualConv + traditionalDrawnOut[0] + rmdExcess;

            settleBaseIncomeTaxAndSurplus(pools, lots, grossSurplus, fundedFromSurplus, unfundedBaseTax,
                    table, ordinaryStack);

            LtcgTaxTable ltcgTable = config.ltcgTaxTableByYear() != null ? config.ltcgTaxTableByYear()[y] : null;
            applyLtcgTax(pools, lots, realizedGainOut[0], dividendIncome, ltcgTable, ordinaryStack, table);

            pools[0] = Math.max(0, pools[0]);
            pools[1] = Math.max(0, pools[1]);
            pools[2] = Math.max(0, pools[2]);
            cashBalance = Math.max(0, cashBalance);

            double totalBalance = pools[0] + pools[1] + pools[2] + cashBalance;
            minBalance = Math.min(minBalance, totalBalance);
            if (yearBalances != null) {
                yearBalances[y] = totalBalance;
            }

            // Perf safety valve for very long horizons: once the year's lot mutations
            // (growth/dividend/withdrawal/reinvest) are done, merge the oldest lots down to the
            // cap so FIFO gain-sale doesn't degrade with unbounded lot growth. Preserves total
            // value and basis exactly (see TaxableLots#consolidateIfNeeded), so it is a no-op at
            // realistic lot counts (~180 for typical horizons) and never changes trial output.
            lots.consolidateIfNeeded(200);
        }

        double finalBalance = Math.max(0, pools[0] + pools[1] + pools[2] + cashBalance);
        boolean traditionalExhausted = config.conversionByYear() != null && pools[1] <= 0;

        return new TrialResult(finalBalance, minBalance, yearBalances, traditionalExhausted, essentialFloorMet);
    }

    /**
     * Deducts a tax amount from pools in order: taxable, traditional, roth.
     * Mutates the pools array in place via the shared {@link PoolTaxCascade}. No gross-up: used only
     * by the Roth-conversion-tax drain, which stays out of audit C2's scope (see {@link
     * #applyTrialConversion}).
     */
    private static void deductTaxFromPools(double tax, double[] pools, TaxableLots lots) {
        double taxableBefore = pools[0];
        double[] after = PoolTaxCascade.deduct(tax, pools[0], pools[1], pools[2]);
        pools[0] = after[0];
        pools[1] = after[1];
        pools[2] = after[2];
        // Mirror the taxable-first tax sale on the lots to preserve the value invariant; the gain it
        // realizes is a second-order tax-payment sale, deliberately left untaxed (no tax-on-tax).
        double taxableSold = taxableBefore - after[0];
        if (taxableSold > 0) {
            lots.sellFifo(taxableSold);
        }
    }

    /**
     * Like {@link #deductTaxFromPools} but additionally grosses up the traditional slice of the
     * drain (audit C2): a tax payment sourced from the traditional pool is itself an ordinary-income
     * distribution and must fund its own tax. Closed-form on the geometric series T + Tm + Tm² + ...
     * = T·m/(1−m) (T = the traditional dollars this drain just pulled, capped at
     * {@link #GROSS_UP_RATE_CAP}), applied as a single additional draw directly against the
     * traditional pool -- the series itself compounds against traditional, since taxable is by
     * definition already exhausted once the base cascade reaches it.
     *
     * <p>Audit C5 coherence: {@code m} is the EXACT marginal rate at the post-draw income point
     * ({@code table.rateAt(stackedBase + T)}), read from the SAME table {@link #simulateTrial}
     * uses for exact incremental pricing -- not the old flat annual chord. {@code stackedBase} is
     * each caller's view of the ordinary income already realized this year BEFORE this drain
     * (base income + actual conversion + traditional spending draw + forced RMD excess, as far as
     * the year has progressed at that call site -- C5 review fix). The closed-form T·m/(1−m)
     * structure itself is deliberately kept (an approximation the design brief explicitly permits
     * to stay), and the gross-up draws of EARLIER tax drains in the same year are not themselves
     * added to later drains' {@code stackedBase} -- that residual second-order sequencing effect
     * mirrors audit C2/T10's own "closed-form, not iterative" scope boundary. {@code table} is
     * null exactly when {@code hasPools} is false, making every call a no-op, same as the old
     * {@code marginalRate=0} behavior.
     */
    private static void deductTaxFromPoolsGrossedUp(double tax, double[] pools, TaxableLots lots,
                                                      OrdinaryTaxTable table, double stackedBase) {
        double traditionalBefore = pools[1];
        deductTaxFromPools(tax, pools, lots);
        double traditionalDrawn = traditionalBefore - pools[1];
        if (traditionalDrawn > 0 && table != null) {
            double m = Math.min(Math.max(table.rateAt(stackedBase + traditionalDrawn), 0.0), GROSS_UP_RATE_CAP);
            double grossUp = traditionalDrawn * m / (1 - m);
            pools[1] = Math.max(0, pools[1] - grossUp);
        }
    }

    /**
     * Seeds the cash-reserve bucket from the first year's spending, drawing it out of the pools in
     * order (taxable, traditional, roth). The taxable draw is mirrored on the lots to keep the value
     * invariant; its gain is a pre-retirement carve-out left untaxed. Returns the initial cash
     * balance (0 when no cash reserve is configured).
     */
    private static double seedCashReserve(double[] pools, TaxableLots lots,
                                           double[] floors, double[] discretionary,
                                           SimulationConfig config) {
        if (config.cashReserveYears() <= 0) {
            return 0;
        }
        double annualSpending = floors[0] + discretionary[0];
        double cashBalance = annualSpending * config.cashReserveYears();
        double cashFromTaxable = Math.min(cashBalance, pools[0]);
        pools[0] -= cashFromTaxable;
        lots.sellFifo(cashFromTaxable);
        double remaining = cashBalance - cashFromTaxable;
        if (remaining > 0) {
            double fromTrad = Math.min(remaining, pools[1]);
            pools[1] -= fromTrad;
            remaining -= fromTrad;
            pools[2] -= remaining;
            pools[2] = Math.max(0, pools[2]);
        }
        return cashBalance;
    }

    /**
     * Settles the year's base-income-tax obligation and surplus deposit (audit A4).
     * Deposits whatever surplus remains after funding the year's base income tax to taxable
     * (at cost), then drains the unfunded remainder straight from the pools via the shared
     * taxable-first cascade -- exactly like the ordinary withdrawal tax and LTCG tax: never funded
     * from the cash reserve, never part of the spending draw. The funding draw IS grossed up when it
     * touches traditional (audit C2), at the year's full ordinary stack (C5 review fix), cross-engine
     * parity with the deterministic engine's {@code extraPoolFundedTax} treatment.
     */
    private static void settleBaseIncomeTaxAndSurplus(double[] pools, TaxableLots lots,
                                                       double grossSurplus, double fundedFromSurplus,
                                                       double unfundedBaseTax, OrdinaryTaxTable table,
                                                       double ordinaryStack) {
        if (grossSurplus > 0) {
            double netSurplus = grossSurplus - fundedFromSurplus;
            if (netSurplus > 0) {
                pools[0] += netSurplus;
                lots.addLot(netSurplus);
            }
        }
        if (unfundedBaseTax > 0) {
            deductTaxFromPoolsGrossedUp(unfundedBaseTax, pools, lots, table, ordinaryStack);
        }
    }

    /**
     * Grows the taxable pool by {@code taxableReturn}: the existing lots appreciate at
     * {@code (taxableReturn − dividendYield)} and the dividend is reinvested as a fresh at-cost lot,
     * booked as the residual to the exact post-growth scalar so {@code pools[0]} still grows at
     * precisely {@code taxableReturn} (bit-identical to the pre-lots path when the yield is 0).
     * Returns this year's qualified-dividend income (taxed as LTCG in {@link #applyLtcgTax}).
     */
    private static double growTaxableWithDividend(double[] pools, TaxableLots lots,
                                                   double taxableReturn, double dividendYield) {
        pools[0] *= (1 + taxableReturn);
        lots.grow(taxableReturn - dividendYield);
        double dividendIncome = Math.max(0, pools[0] - lots.totalValue());
        lots.addLot(dividendIncome);
        return dividendIncome;
    }

    /**
     * Applies the long-term capital-gains tax on this year's realized spending gain plus qualified
     * dividend, exactly (audit C5) via {@code ltcgTable}, evaluated at the ACTUAL full ordinary
     * stack for this trial-year ({@code ordinaryStack} = base income + actual capped Roth
     * conversion + actual traditional spending draw + forced RMD excess) -- fixing the old
     * {@code LtcgRateCalculator}'s omission of every same-year draw from the floor it probed,
     * which could silently under-price a gain those draws push across the 0%/15% boundary
     * (C5 review fix: the floor now also includes the RMD excess and uses the CAPPED actual
     * conversion, not the pre-cap target).
     *
     * <p>Like the RMD/withdrawal tax it is an additional outflow drained taxable-first from the
     * pools (retirement-scoped; the MC path is retirement-only), grossed up when it touches
     * traditional (audit C2) -- using the ORDINARY table at the same stacked point for the
     * gross-up rate, not the LTCG rate: the traditional draw funding this bill is itself ordinary
     * income once withdrawn, regardless of what kind of tax it is paying. A {@code null} table (no
     * capital-gains calculator wired) or a non-positive net LTCG income leaves the pools
     * untouched.
     */
    private static void applyLtcgTax(double[] pools, TaxableLots lots, double realizedGain,
                                      double dividendIncome, LtcgTaxTable ltcgTable,
                                      double ordinaryStack, OrdinaryTaxTable ordinaryTable) {
        if (ltcgTable == null) {
            return;
        }
        double ltcgIncome = realizedGain + dividendIncome;
        if (ltcgIncome <= 0) {
            return;
        }
        double ltcgTax = ltcgTable.taxAt(Math.max(0, ordinaryStack), ltcgIncome);
        if (ltcgTax > 0) {
            deductTaxFromPoolsGrossedUp(ltcgTax, pools, lots, ordinaryTable, ordinaryStack);
        }
    }

    /**
     * Computes this year's Required Minimum Distribution from the prior-year-end traditional
     * balance ({@code pools1PreGrowth}), per the IRS Uniform Lifetime Table. Returns {@code 0}
     * before the owner reaches {@code rmdStartAge}, when there is no traditional balance, or
     * when the table has no distribution period for the age (outside 72-120).
     */
    private static double computeYearRmd(double pools1PreGrowth, int age, int rmdStartAge) {
        if (age < rmdStartAge || pools1PreGrowth <= 0) {
            return 0;
        }
        double divisor = RmdCalculator.distributionPeriod(age);
        return divisor > 0 ? pools1PreGrowth / divisor : 0;
    }

    /**
     * Forces the RMD excess out of the traditional pool when the spend withdrawal didn't already
     * draw enough to satisfy it: it's a real, legally-required distribution even when the retiree
     * doesn't need the cash for spending. The after-tax remainder is reinvested to taxable; the
     * tax on the forced distribution leaves the portfolio entirely (it is not itself reinvested).
     *
     * <p>Audit C5 review fix: the excess's tax is priced EXACTLY as the next increment of the
     * year's ordinary stack -- {@code table.incrementalTax(stackedBase, extra)} where
     * {@code stackedBase} = base income + actual conversion + the traditional spending draw --
     * replacing the earlier single-point-rate approximation ({@code extra * rateAt(base)}), which
     * both missed bracket crossings within the excess and ignored the same-year income beneath it.
     * A {@code null} table (no tax modeling) leaves the forced distribution untaxed, matching the
     * old {@code marginalRate = 0} behavior.
     *
     * @return the forced excess actually distributed (0 when none), so the caller can include it
     *         in the year's ordinary stack for the remaining tax bills (LTCG floor, gross-ups)
     */
    private static double forceRmdExcess(double[] pools, TaxableLots lots, double rmd,
                                          double traditionalDrawnForSpending,
                                          OrdinaryTaxTable table, double stackedBase) {
        if (rmd <= 0) {
            return 0;
        }
        double extra = Math.max(0, rmd - traditionalDrawnForSpending);
        extra = Math.min(extra, pools[1]);
        if (extra > 0) {
            pools[1] -= extra;
            double taxExtra = table != null ? table.incrementalTax(stackedBase, extra) : 0;
            double reinvested = extra - taxExtra;
            pools[0] += reinvested;
            lots.addLot(reinvested);   // after-tax RMD reinvested to taxable at cost
        }
        return extra;
    }

    /**
     * Executes a Roth conversion for this trial year: transfers from traditional to roth,
     * then deducts conversion tax from pools.
     *
     * <p>Audit C5 review fix: when a tax table is available, the conversion tax is priced EXACTLY
     * in-trial as {@code table.incrementalTax(base, actualConv)} -- conversions are the first
     * ordinary income stacked directly on the year's base -- using the ACTUAL
     * traditional-balance-capped amount. This replaces the precomputed
     * {@code conversionTaxByYear[y]} (whose pro-rata scaling under a cap linearly scales a CONVEX
     * tax, overstating the capped amount's true tax; the schedule's own
     * {@code ConversionSimulator#computeIncrementalTax} stacks correctly but only for the
     * UNCAPPED target on the optimizer's own income model). The precomputed figure remains the
     * fallback for no-table trials, preserving their legacy behavior.
     *
     * @return the actual (capped) conversion amount executed -- 0 when no conversion runs -- so
     *         the caller can stack every later ordinary pricing call on top of it
     */
    private static double applyTrialConversion(double[] pools, TaxableLots lots, double[] conversionByYear,
                                                double[] conversionTaxByYear, int y, int age,
                                                OrdinaryTaxTable table, double base) {
        if (conversionByYear == null || conversionByYear[y] <= 0 || pools[1] <= 0) {
            return 0;
        }
        double actualConv = Math.min(conversionByYear[y], pools[1]);
        pools[1] -= actualConv;
        pools[2] += actualConv;
        double actualTax;
        if (table != null) {
            actualTax = table.incrementalTax(base, actualConv);
        } else {
            actualTax = (actualConv < conversionByYear[y])
                    ? conversionTaxByYear[y] * (actualConv / conversionByYear[y])
                    : conversionTaxByYear[y];
        }
        if (age < RetirementAges.EARLY_WITHDRAWAL_AGE) {
            double taxPaid = Math.min(actualTax, Math.max(0, pools[0]));
            pools[0] -= taxPaid;
            lots.sellFifo(taxPaid);   // conversion-tax sale synced; gain untaxed (second-order)
        } else {
            deductTaxFromPools(actualTax, pools, lots);
        }
        return actualConv;
    }

    /**
     * Deducts withdrawals and tax from pools, handling cash reserve logic. Returns updated cash
     * balance. {@code traditionalDrawnOut[0]} is set to the dollar amount actually debited from
     * {@code pools[1]} for this year's spending draw. {@code base} is the ordinary income already
     * realized BEFORE this call (income-source base + the year's actual Roth conversion); the
     * withdrawal-tax drain's gross-up point additionally stacks the branch's own actual
     * traditional spending draw on it. Package-private (mirrors {@link #splitWithdrawal}) so the
     * cash-reserve down-year branches can be unit tested directly instead of only through the
     * full {@link #simulateTrial} pipeline.
     */
    static double applyTrialWithdrawals(double[] pools, TaxableLots lots, double[] realizedGainOut,
                                         double[] traditionalDrawnOut, double cashBalance,
                                         PoolWithdrawal drawn, double withdrawalTax,
                                         double withdrawal, double spending,
                                         boolean hasPools, int cashReserveYears,
                                         double portfolioReturn, OrdinaryTaxTable table, double base) {
        if (cashReserveYears > 0) {
            if (portfolioReturn < 0) {
                // Down-year cash reserve draw: cash covers as much of the spending withdrawal as
                // it can; the equity pools fund only the remainder, scaled pro-rata across all
                // three pools -- identical semantics whether or not withdrawal tax is estimated
                // (hasPools). When cash fully covers the withdrawal the equity pools are debited
                // NOTHING for the spending draw; that is the entire point of the reserve (see
                // TrialSimulatorReturnTest's pinned non-pool test).
                double cashDraw = Math.min(withdrawal, cashBalance);
                double equityDraw = withdrawal - cashDraw;
                double drawnTotal = drawn.total();
                if (drawnTotal > 0 && equityDraw > 0) {
                    double scale = equityDraw / Math.max(drawnTotal, equityDraw);
                    double spendingSale = drawn.taxable() * scale;
                    double tradDrawn = drawn.traditional() * scale;
                    pools[0] -= spendingSale;
                    pools[1] -= tradDrawn;
                    pools[2] -= drawn.roth() * scale;
                    realizedGainOut[0] += lots.sellFifo(spendingSale);
                    traditionalDrawnOut[0] = tradDrawn;
                }
                // The withdrawal tax is a separate, full-amount outflow paid straight from the
                // pools via the shared cascade (grossed up when it touches traditional -- audit
                // C2) -- the cash reserve funds spending only, never tax, matching the up-market
                // and no-reserve branches below. (Replaces the old dimensionally-wrong
                // tax^2/portfolio partial deduction, and the old double debit when cash fully
                // covered the draw.)
                if (hasPools) {
                    deductTaxFromPoolsGrossedUp(withdrawalTax, pools, lots, table,
                            base + traditionalDrawnOut[0]);
                }
                return cashBalance - cashDraw;
            } else {
                // Up market: normal withdrawal + replenish cash
                pools[0] -= drawn.taxable();
                realizedGainOut[0] += lots.sellFifo(drawn.taxable());
                pools[1] -= drawn.traditional();
                pools[2] -= drawn.roth();
                traditionalDrawnOut[0] = drawn.traditional();
                if (hasPools) {
                    deductTaxFromPoolsGrossedUp(withdrawalTax, pools, lots, table,
                            base + traditionalDrawnOut[0]);
                }
                double targetCash = spending * cashReserveYears;
                double replenishment = Math.min(
                        Math.max(0, targetCash - cashBalance),
                        Math.max(0, pools[0] + pools[1] + pools[2])
                                * CASH_REPLENISHMENT_RATE);
                pools[0] -= replenishment;
                lots.sellFifo(replenishment);   // taxable→cash reserve churn; gain untaxed (second-order)
                if (pools[0] < 0) {
                    pools[1] += pools[0];
                    pools[0] = 0;
                }
                return cashBalance + replenishment;
            }
        } else {
            // No cash reserve
            pools[0] -= drawn.taxable();
            realizedGainOut[0] += lots.sellFifo(drawn.taxable());
            pools[1] -= drawn.traditional();
            pools[2] -= drawn.roth();
            traditionalDrawnOut[0] = drawn.traditional();
            if (hasPools) {
                deductTaxFromPoolsGrossedUp(withdrawalTax, pools, lots, table,
                        base + traditionalDrawnOut[0]);
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
}
