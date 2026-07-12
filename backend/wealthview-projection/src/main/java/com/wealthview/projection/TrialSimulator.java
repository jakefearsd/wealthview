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
     * Simulated guardrail-adaptation rule inputs (audit C9). When a
     * {@link SimulationConfig#adaptation()} is present the trial adapts the year's DISCRETIONARY
     * spending in-simulation toward the DISPLAYED spending corridor instead of running the fixed
     * planned schedule; when it is {@code null} the trial runs the fixed schedule exactly as before
     * (byte-identical no-rules behavior). A single instance is built once per run and shared across
     * every trial (allocation-free hot loop), so this is NOT a per-trial object.
     *
     * <p>All four arrays are indexed by projection year:
     * <ul>
     *   <li>{@code corridorLow}/{@code corridorHigh} — the final, smoothed-and-clamped displayed
     *       corridor (the very arrays that populate {@code GuardrailYearlySpending}). The rule keys
     *       off {@code corridorLow}: only the LOWER guardrail can bind in-simulation, because
     *       constraint (2) caps adapted spending at the planned schedule (no prosperity ratchet) and
     *       {@code corridorHigh} is always {@code >=} the plan — the upper band is display-only here.
     *   <li>{@code expectedStartBalance} — the no-adaptation median start-of-year total portfolio
     *       (year 0 = initial portfolio; year y&gt;0 = the prior year's median end-of-year balance
     *       from the headline terminal pass). The trial's own start-of-year balance is measured
     *       against this to form the {@code trialPortfolio / expectedMedianPortfolio[y]} sensitivity.
     * </ul>
     * {@code maxAnnualAdjustmentRate} is the existing user knob bounding the year-over-year change.
     */
    record GuardrailAdaptation(
            double[] corridorLow, double[] corridorHigh,
            double[] expectedStartBalance, double maxAnnualAdjustmentRate) {}

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
            double initTaxableBasis, LtcgTaxTable[] ltcgTaxTableByYear, double dividendYield,
            GuardrailAdaptation adaptation, double[] rentalIncomeByYear
    ) {
        /**
         * Back-compat constructor predating T18a-3: no per-year net rental income to thread into
         * the NIIT Net Investment Income base (null -- every year contributes zero rental to NII,
         * byte-identical to pre-fix behavior).
         */
        // ExcessiveParameterList: mirrors the record's own canonical constructor (pre-rental shape)
        // so existing positional call sites keep compiling and behaving identically.
        @SuppressWarnings("PMD.ExcessiveParameterList")
        SimulationConfig(
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
                double initTaxableBasis, LtcgTaxTable[] ltcgTaxTableByYear, double dividendYield,
                GuardrailAdaptation adaptation) {
            this(initTaxable, initTraditional, initRoth, withdrawalOrder,
                    ordinaryTaxTableByYear, ordinaryBaseIncomeByYear, conversionByYear, conversionTaxByYear,
                    retirementAge, dsBracketCeilingByYear, cashReserveYears, cashReturnRate,
                    trackYearBalances, taxableReturns, traditionalReturns, rothReturns, rmdStartAge,
                    initTaxableBasis, ltcgTaxTableByYear, dividendYield, adaptation, null);
        }

        /**
         * Back-compat constructor (audit C9): every pre-C9 call site (the sustainability search, the
         * headline terminal pass, and the unit tests) builds a fixed-schedule config with no
         * simulated guardrail rule and no rental income. Defaulting {@code adaptation} to
         * {@code null} keeps {@link #simulateTrial} on the byte-identical no-rules path for all of
         * them.
         */
        // ExcessiveParameterList: mirrors the record's own canonical constructor (pre-adaptation
        // shape) so existing positional call sites keep compiling and behaving identically.
        @SuppressWarnings("PMD.ExcessiveParameterList")
        SimulationConfig(
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
                double initTaxableBasis, LtcgTaxTable[] ltcgTaxTableByYear, double dividendYield) {
            this(initTaxable, initTraditional, initRoth, withdrawalOrder,
                    ordinaryTaxTableByYear, ordinaryBaseIncomeByYear, conversionByYear, conversionTaxByYear,
                    retirementAge, dsBracketCeilingByYear, cashReserveYears, cashReturnRate,
                    trackYearBalances, taxableReturns, traditionalReturns, rothReturns, rmdStartAge,
                    initTaxableBasis, ltcgTaxTableByYear, dividendYield, null);
        }
    }

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

        // Audit C9: `prevSpending` carries the prior year's adapted total spending so the
        // year-over-year adjustment can be bounded; seeded to year 0's planned total so the first
        // year runs the plan (ratio ~= 1 -> within corridor). Inert (a dead store) on the no-rules
        // path (config.adaptation() == null), which every pre-C9 caller takes.
        double prevSpending = floors[0] + discretionary[0];

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

            // Audit C9: the trial's start-of-year total portfolio (pre-growth pools + pre-growth
            // cash) — the signal the simulated guardrail rule measures against the no-adaptation
            // median. Captured before this year's growth is applied below.
            double trialStartTotal = preGrowthTotal + cashBalance;

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
            // STACKING ORDER (T18a-1 review fix): the year's ordinary income accumulates through
            // the loop body in execution order -- base income, then the forced RMD (IRS ordering:
            // the year's RMD must be distributed BEFORE any Roth conversion), then the conversion,
            // then the traditional spending draw -- and every pricing call stacks its own amount
            // on top of everything realized BEFORE it, so the year's summed ordinary tax telescopes
            // to exactly taxAt(base+rmd+conv+draw) - taxAt(base) (pinned by
            // TrialSimulatorReturnTest's composition-identity test). `table` is null when hasPools
            // is false (no tax modeling this trial), which naturally makes every pricing and
            // gross-up call a no-op.
            OrdinaryTaxTable table = hasPools ? config.ordinaryTaxTableByYear()[y] : null;
            double base = hasPools ? config.ordinaryBaseIncomeByYear()[y] : 0.0;

            // T18a-1: force the RMD out of traditional FIRST -- before any Roth conversion --
            // mirroring the deterministic engine's PoolStrategy#forceRmd. The FULL rmd is
            // force-distributed unconditionally, independent of what the spending draw later
            // decides (replaces the old end-of-year forceRmdExcess, which only forced the amount
            // beyond whatever the spend draw happened to already pull from traditional).
            double rmdForced = forceRmdFirst(pools, lots, rmd, table, base);

            // Roth conversion execution -- stacks on base + the forced RMD (T18a-1).
            // actualConv is the traditional-balance-CAPPED amount actually converted (0 when no
            // conversion runs); every later ordinary pricing call stacks on it too.
            double actualConv = applyTrialConversion(pools, lots, config.conversionByYear(),
                    config.conversionTaxByYear(), y, age, table, base + rmdForced);

            // Audit C9: with-rules pass adapts the year's total spending toward the displayed
            // corridor from the trial's own portfolio state (cutting discretionary in down markets,
            // recovering toward plan otherwise -- floor inviolate, never above plan); no-rules pass
            // returns the fixed planned schedule (byte-identical to pre-C9). Only the SPENDING INPUT
            // differs -- the entire tax/withdrawal/RMD/LTCG year-step below is shared verbatim (no
            // forked tax logic). The chained assignment updates prevSpending in lock-step without
            // adding a statement to this NCSS-capped hot method.
            double spending = prevSpending = resolveYearSpending(config, y, floors[y],
                    discretionary[y], trialStartTotal, prevSpending);
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
            // base + the forced RMD + this year's ACTUAL conversion income (T18a-1 order) --
            // taxAt(base + rmd + actualConv + draw) - taxAt(base + rmd + actualConv) -- correctly
            // pricing any bracket crossing within the draw AND the bracket position the RMD/
            // conversion already pushed the year into.
            double withdrawalTax = 0;
            if (hasPools && drawn.traditional() > 0) {
                withdrawalTax = table.incrementalTax(base + rmdForced + actualConv, drawn.traditional());
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
                    config.cashReserveYears(), portfolioReturn, table, base + rmdForced + actualConv);
            double cashDrawn = Math.max(0, cashBeforeWithdrawals - cashBalance);

            // The base-income-tax deduction is NOT part of drawn/cashDrawn (it drains via
            // deductTaxFromPoolsGrossedUp below, like withdrawalTax), so this metric already measures
            // spending resources only -- identical to its pre-A4 shape.
            double resourcesForSpending = income[y] + drawn.total() + cashDrawn;
            if (resourcesForSpending < floors[y] - 1e-6) {
                essentialFloorMet = false;
            }

            // Full ordinary stack realized this year, in T18a-1's rmd -> conversion -> spending
            // draw order -- the point every remaining tax bill's funding draw (and the LTCG
            // bracket floor) stacks on.
            double ordinaryStack = base + rmdForced + actualConv + traditionalDrawnOut[0];

            settleBaseIncomeTaxAndSurplus(pools, lots, grossSurplus, fundedFromSurplus, unfundedBaseTax,
                    table, ordinaryStack);

            LtcgTaxTable ltcgTable = config.ltcgTaxTableByYear() != null ? config.ltcgTaxTableByYear()[y] : null;
            // T18a-3: this year's net rental income, threaded into the NIIT Net Investment Income
            // base only (never the LTCG bracket tax) -- zero when no rental array is configured.
            double rentalIncome = config.rentalIncomeByYear() != null ? config.rentalIncomeByYear()[y] : 0.0;
            applyLtcgTax(pools, lots, realizedGainOut[0], dividendIncome, ltcgTable, ordinaryStack, table,
                    rentalIncome);

            clampPoolsNonNegative(pools);
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

    /** Floors the three equity pools at zero after the year's mutations. Extracted from
     * {@link #simulateTrial} so the NCSS-capped hot method stays within budget. */
    // UseVarargs: {@code pools} is the fixed three-element pool state array mutated in place, not a
    // variable argument list.
    @SuppressWarnings("PMD.UseVarargs")
    private static void clampPoolsNonNegative(double[] pools) {
        pools[0] = Math.max(0, pools[0]);
        pools[1] = Math.max(0, pools[1]);
        pools[2] = Math.max(0, pools[2]);
    }

    /**
     * Resolves this trial-year's total spending. On the no-rules path (no
     * {@link GuardrailAdaptation} on the config) this is the fixed planned schedule
     * {@code floor + discretionary}, byte-identical to pre-C9. On the with-rules pass it is the
     * corridor-coupled adaptation of {@link #adaptYearSpending} (audit C9).
     */
    private static double resolveYearSpending(SimulationConfig config, int y,
                                               double floor, double discretionary,
                                               double trialStartTotal, double prevSpending) {
        double plannedSpending = floor + discretionary;
        GuardrailAdaptation adaptation = config.adaptation();
        return adaptation != null
                ? adaptYearSpending(adaptation, y, plannedSpending, floor, trialStartTotal, prevSpending)
                : plannedSpending;
    }

    /**
     * Applies the simulated guardrail-adaptation rule (audit C9) for one trial-year: returns the
     * TOTAL spending the trial should attempt this year, adapting the planned schedule toward the
     * displayed corridor based on the trial's actual portfolio state.
     *
     * <p><b>Corridor coupling — precise semantics.</b> The trigger signal is a portfolio-ratio
     * PROXY — {@code impliedSpending = plannedSpending * (trialStartBalance /
     * expectedMedianStart[y])} — GATED BY the displayed corridor thresholds; it is NOT derived the
     * way the corridor itself was ({@link SpendingCorridorCalculator} builds the band from
     * cross-trial percentile dispersion of sustainable capacity, whereas this signal is a simple
     * ratio scaling of the plan by the trial's portfolio state relative to the no-adaptation
     * median). When the proxy falls below the displayed lower guardrail ({@code corridorLow[y]})
     * the trial CUTS discretionary toward the proxy ITSELF — so simulated spending CAN fall below
     * {@code corridorLow[y]}, bounded only by the floor and the year-over-year rate cap. Otherwise
     * it RECOVERS toward the planned schedule (the upper guardrail {@code corridorHigh[y]} is the
     * ceiling of that recovery, but the no-ratchet cap at {@code plannedSpending <=
     * corridorHigh[y]} always binds first — see constraint 2, so the upper band is display-only
     * in-simulation). The with-rules success rate therefore measures "success when following this
     * specific ratio-cut rule", not "spending always kept inside the shown corridor".
     *
     * <p><b>Bounds.</b> The year-over-year change is capped at {@code maxAnnualAdjustmentRate}
     * relative to the prior year's spending (the same relative form the pre-optimization
     * {@link SpendingSmoother#applyYearOverYearSmoothing} uses). Result is then capped at
     * {@code plannedSpending} (never exceed the plan — recovering cuts, never an unbounded
     * prosperity ratchet) and floored at {@code floor} (the essential floor is inviolate — cuts
     * never take spending below it). Because floors are never cut, the adaptation cannot cause a
     * floor shortfall within its own year; across cumulative multi-year tax paths (RMD force-outs,
     * gross-ups, LTCG interactions) with-rules-&gt;=-no-rules success monotonicity is NOT formally
     * proven — it is empirically pinned by the integration tests (including the RMD/tax-dynamics
     * pairwise pins) and a 60,000-trial-pair adversarial probe that found zero floor or
     * final-balance regressions.
     *
     * <p>Pure, allocation-free, no branching on trial identity — safe for the hot loop.
     */
    static double adaptYearSpending(GuardrailAdaptation adapt, int y,
                                     double plannedSpending, double floor,
                                     double trialStartBalance, double prevSpending) {
        double rate = adapt.maxAnnualAdjustmentRate();
        double expected = adapt.expectedStartBalance()[y];
        double ratio = expected > 0 ? trialStartBalance / expected : 1.0;
        double impliedSpending = plannedSpending * ratio;

        double target;
        if (impliedSpending < adapt.corridorLow()[y]) {
            // Lower guardrail breached: cut toward the implied capacity.
            target = impliedSpending;
        } else {
            // At/above the lower guardrail: recover toward the planned schedule, ceilinged by the
            // upper guardrail (which is >= plan, so the plan cap below binds first).
            target = Math.min(plannedSpending, adapt.corridorHigh()[y]);
        }

        double maxUp = prevSpending * (1 + rate);
        double maxDown = prevSpending * (1 - rate);
        double adapted = Math.max(maxDown, Math.min(maxUp, target));
        adapted = Math.min(adapted, plannedSpending);   // no prosperity ratchet
        return Math.max(adapted, floor);                // essential floor inviolate
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
     * untouched. {@code netRentalIncome} (T18a-3) joins the NIIT Net Investment Income base only
     * (never the LTCG bracket tax) -- see {@link LtcgTaxTable#taxAt(double, double, double)}.
     */
    private static void applyLtcgTax(double[] pools, TaxableLots lots, double realizedGain,
                                      double dividendIncome, LtcgTaxTable ltcgTable,
                                      double ordinaryStack, OrdinaryTaxTable ordinaryTable,
                                      double netRentalIncome) {
        if (ltcgTable == null) {
            return;
        }
        double ltcgIncome = realizedGain + dividendIncome;
        if (ltcgIncome <= 0) {
            return;
        }
        double ltcgTax = ltcgTable.taxAt(Math.max(0, ordinaryStack), ltcgIncome, netRentalIncome);
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
     * T18a-1: forces this year's RMD out of the traditional pool FIRST -- before any Roth
     * conversion or spending withdrawal (IRS ordering: the year's RMD must be distributed before
     * any conversion) -- mirroring the deterministic engine's {@code PoolStrategy#forceRmd}.
     * Replaces the old end-of-year {@code forceRmdExcess}, which forced only the amount beyond
     * whatever the spend draw happened to already pull from traditional; under RMD-first ordering
     * the FULL rmd is force-distributed unconditionally, independent of any later withdrawal
     * decision. The after-tax remainder is reinvested to taxable; the tax on the forced
     * distribution leaves the portfolio entirely (it is not itself reinvested, and is not routed
     * through the C2 gross-up cascade -- the same direct-leak design the old
     * {@code forceRmdExcess} used).
     *
     * @return the forced amount actually distributed (0 when {@code rmd <= 0} or traditional is
     *         already exhausted) -- the FIRST ordinary-income stack this trial-year realizes;
     *         every later pricing call (conversion, spending draw, their gross-ups) stacks on top
     *         of it.
     */
    private static double forceRmdFirst(double[] pools, TaxableLots lots, double rmd,
                                         OrdinaryTaxTable table, double base) {
        if (rmd <= 0) {
            return 0;
        }
        double extra = Math.min(rmd, pools[1]);
        if (extra > 0) {
            pools[1] -= extra;
            double taxExtra = table != null ? table.incrementalTax(base, extra) : 0;
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
