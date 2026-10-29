package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.strategy.WithdrawalOrder;

/**
 * Runs single-path Monte Carlo trials for the spending optimizer: year-by-year
 * portfolio evolution with growth, Roth conversions, withdrawals, withdrawal tax,
 * income surplus, and early-withdrawal penalties.
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

    // Household task 6: the owner-aware double[5] pool layout {joint-taxable, trad-primary,
    // trad-spouse, roth-primary, roth-spouse} and its low-level mechanics live in TrialPools. Task
    // 17 fully encapsulated pools/lots behind TrialPools instance methods, so this class no longer
    // indexes the array directly -- only the TRAD_P/TRAD_S/ROTH_P/ROTH_S owner-slot pair constants
    // survive here, as debitPair() arguments. Single-person trials leave the spouse slots
    // (TRAD_S/ROTH_S) at 0 for the whole trial, so every branch reduces to the pre-household 3-pool
    // arithmetic bit-for-bit (the anchor).
    private static final int TRAD_P = TrialPools.TRAD_P;
    private static final int TRAD_S = TrialPools.TRAD_S;
    private static final int ROTH_P = TrialPools.ROTH_P;
    private static final int ROTH_S = TrialPools.ROTH_S;

    // Sub-project B (stochastic mortality), task 6: the two survivor-regime identities indexing
    // {@link SimulationConfig#survivorRegimes}. Which one is spliced in from a trial's first-death
    // transition index is selected per trial by {@code household.survivorIsPrimary()} — the SAME flag
    // that drives the pool rollover/step-up direction, so the income/tax regime and the pool mechanics
    // always agree on who survived. Named constants (not literals) keep the hot-loop selection self-
    // documenting.
    static final int PRIMARY_SURVIVES = 0;
    static final int SPOUSE_SURVIVES = 1;

    /**
     * Task 17: {@link #applyTrialWithdrawals}'s per-year result -- replaces the old two
     * single-element out-arrays the caller allocated every year ({@code realizedGainOut},
     * {@code traditionalDrawnOut}) with a plain returned record, matching every sibling helper in
     * this class. {@code realizedGain} is the FIFO long-term gain realized by this call's taxable
     * sale (spending sale only -- the secondary tax sale sells FIFO but its gain is deliberately
     * excluded, see {@link #applyTrialWithdrawals}); {@code traditionalDrawn} the dollars actually
     * debited from traditional for the spending draw (0 when the draw never reached traditional).
     */
    record WithdrawalOutcome(double realizedGain, double traditionalDrawn) {}

    /** Per-trial simulation result. */
    record TrialResult(
            double finalBalance,
            double minBalance,
            double[] yearBalances,       // null when trackYearBalances is false
            boolean traditionalExhausted,
            boolean success,
            double tentYearsCovered      // NaN without a tent
    ) {
        TrialResult(double finalBalance, double minBalance, double[] yearBalances, boolean traditionalExhausted,
                    boolean success) {
            this(finalBalance, minBalance, yearBalances, traditionalExhausted, success, Double.NaN);
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
    // conditions (tax mode, tracking). The path count is multiplicative but each
    // branch is a small guarded block, so the method is far simpler than its NPath number.
    @SuppressWarnings("PMD.NPathComplexity")
    TrialResult simulateTrial(
            double[] income, double[] surplusTax,
            double[] floors, double[] discretionary,
            int years, SimulationConfig config) {

        boolean hasConversions = config.conversionByYear() != null;
        boolean hasPools = config.ordinaryTaxTableByYear() != null;

        // Household task 6: owner-aware double[5] pools + the parallel FIFO-lot view (Task 6),
        // consolidated (Task 17) into ONE mutable per-trial TrialPools -- {taxable, traditional, 0,
        // roth, 0} for a null household, reducing every branch below to the pre-household 3-pool
        // arithmetic bit-for-bit. See TrialPools#seed for the value invariant
        // (lots.totalValue() == pools[JOINT_TAXABLE]) and its one manual re-pinning seam. ONE
        // allocation for the whole trial -- no further allocations inside the year loop below.
        HouseholdSim household = config.household();
        TrialPools tp = TrialPools.seed(config, household);
        // Resolve the withdrawal order once per trial (constant for the run) so the
        // per-year splitWithdrawal calls do no String.equals work.
        WithdrawalOrder order = WithdrawalOrder.fromString(config.withdrawalOrder());

        // Sub-project B (stochastic mortality), task 6: the survivor spending factor in effect at
        // year 0 -- non-1.0 only in the rare stochastic trial whose first death lands AT retirement
        // (transition index 0). 1.0 for the fixed-death / single-person path (survivorRegimes null),
        // so the seed reads floors[0]+discretionary[0] unchanged (the byte-identical anchor).
        double seedFactor = regimeAt(config, household, 0) != null ? config.survivorFactor() : 1.0;
        double minBalance = tp.poolTotal();
        double[] yearBalances = config.trackYearBalances() ? new double[years] : null;
        boolean essentialFloorMet = true;

        // Household task 6: trials truncate at the second (survivor's) death — the bequest year.
        // Single-person / both-die-beyond-horizon households leave this at the full horizon (the
        // byte-identical anchor). yearBalances beyond the truncation carry the final bequest forward
        // (filled after the loop) so a truncated household trial does not inject zeros into the
        // cross-trial percentile math.
        int loopYears = household != null ? Math.min(years, household.truncateYearIndex()) : years;

        // Audit C9: `prevSpending` carries the prior year's adapted total spending so the
        // year-over-year adjustment can be bounded; seeded to year 0's planned total so the first
        // year runs the plan (ratio ~= 1 -> within corridor). Inert (a dead store) on the no-rules
        // path (config.adaptation() == null), which every pre-C9 caller takes. Scaled by the year-0
        // survivor factor (task 6) for the transition-at-retirement stochastic corner; seedFactor is
        // 1.0 on every other path, so this is floors[0]+discretionary[0] unchanged.
        double prevSpending = (floors[0] + discretionary[0]) * seedFactor;

        for (int y = 0; y < loopYears; y++) {
            double taxableReturn = config.taxableReturns()[y];
            double traditionalReturn = config.traditionalReturns()[y];
            double rothReturn = config.rothReturns()[y];

            double preGrowthTotal = tp.poolTotal();

            // Bond tent: an empty or absent tent takes the untented path below, exactly.
            TrialTent tent = activeTent(config.tent());
            int tentYear = tent == null ? 0 : tent.yearsBeforeRetirement() + y;
            double expectedAfterGrowth = tent == null ? 0
                    : tent.prepareGrowth(tp.taxable(), tp.traditionalTotal(), tp.rothTotal(), taxableReturn,
                            traditionalReturn, rothReturn, tentYear);

            // Audit C9: the trial's start-of-year total portfolio (pre-growth pools) — the signal
            // the simulated guardrail rule measures against the no-adaptation median. Captured
            // before this year's growth is applied below.
            double trialStartTotal = preGrowthTotal;

            // Household task 6: each owner's prior-year-end traditional balance — the IRS RMD basis
            // for that owner's own stream this year, snapshotted before this year's growth. Spouse is
            // 0 for a single-person trial, so its stream contributes nothing.
            double tradPPreGrowth = tp.traditionalPrimary();
            double tradSPreGrowth = tp.traditionalSpouse();

            // Sub-project B (stochastic mortality), task 6: this year's spliced regime view. On the
            // fixed-death / single-person path (config.survivorRegimes() == null) and every year BEFORE
            // this trial's first-death transition index it carries today's base-array/config values
            // verbatim (the byte-identical anchor); from the transition index forward it carries the
            // survivor regime for THIS trial's survivor identity plus the survivor-factor-scaled floor
            // and discretionary. It also resolves this year's exact ordinary tax `table` and stacking
            // `base` (audit C5, null/0 when hasPools is false) -- extracted here so simulateTrial's hot
            // body stays under the PMD NCSS/cognitive-complexity gates. STACKING ORDER (T18a-1 review
            // fix, audit C1): base income, then realized ordinary interest, then the forced RMD, then
            // the conversion, then the traditional draw -- each pricing call stacks on everything
            // realized before it, so the year's summed ordinary tax telescopes to exactly
            // taxAt(base+interest+rmd+conv+draw) - taxAt(base) (TrialSimulatorReturnTest).
            YearRegimeView view = resolveYearRegimeView(
                    config, household, y, hasPools, income, surplusTax, floors, discretionary);
            OrdinaryTaxTable table = view.table();

            // Audit C1: splits the taxable pool's annual distribution by its own equity share --
            // the equity portion stays qualified-dividend income (dividendIncome, unchanged
            // treatment); the bond+cash portion is ordinary-interest income, priced and funded
            // immediately (stacked directly on `base`, before RMD/conversion/draw). Bundled into
            // one call to keep this NCSS-capped hot method lean. taxableEquityShare == 1.0 (every
            // pre-C1 caller) makes this byte-identical to the old growTaxableWithDividend
            // (interest income always 0, baseWithInterest == base).
            var growthResult = tent == null
                    ? TrialGrowth.applyGrowthAndInterestTax(tp, taxableReturn, config.dividendYield(),
                            config.interestYield(), config.taxableEquityShare(), table, view.base())
                    : TrialGrowth.applyTentGrowth(tp, tent, config, new TrialGrowth.TentGrowthYear(tentYear,
                            taxableReturn, traditionalReturn, rothReturn, table, view.base()));
            double dividendIncome = growthResult.dividendIncome();
            double baseWithInterest = growthResult.baseWithInterest();
            if (tent == null) {
                tp.growNonTaxablePools(traditionalReturn, rothReturn);
            }

            int age = config.retirementAge() + y;

            // Household task 6 — TWO RMD streams (IRS ordering: distribute before any Roth conversion),
            // in the pinned ordinary-stack order base+interest → RMD-P → RMD-S. Each stream keys off
            // its owner's own prior-year balance, age, and SECURE-2.0 start age; for a single-person
            // trial the spouse stream is 0, so this reduces to the single stream bit-for-bit.
            // rmdComputed (pre-force) drives the dynamic-sequencing bracket-space calc below.
            var rmd = tp.forceRmdStreams(household, tradPPreGrowth, tradSPreGrowth,
                    age, config.rmdStartAge(), table, baseWithInterest);
            double rmdComputed = rmd.computed();
            double rmdForced = rmd.forced();

            // Household task 6 — the first-death transition (atomic, once, at the year boundary):
            // after both owners' year-of-death RMDs and before any conversion or draw. Rolls the
            // deceased's traditional/Roth into the survivor's own pools (conservation-preserving) and
            // steps up the joint-taxable lots' basis. Never fires for a single-person trial (null
            // household).
            if (household != null && y == household.transitionYearIndex()) {
                tp.applyFirstDeathTransition(household);
            }

            // Roth conversion execution -- stacks on base + interest + the forced RMD (T18a-1).
            // actualConv is the traditional-balance-CAPPED amount actually converted (0 when no
            // conversion runs); every later ordinary pricing call stacks on it too.
            double actualConv = applyTrialConversion(tp, config.conversionByYear(),
                    config.conversionTaxByYear(), y, age, config.earlyAccessAge(), table,
                    baseWithInterest + rmdForced);

            // Audit C9: with-rules pass adapts the year's total spending toward the displayed
            // corridor from the trial's own portfolio state (cutting discretionary in down markets,
            // recovering toward plan otherwise -- floor inviolate, never above plan); no-rules pass
            // returns the fixed planned schedule (byte-identical to pre-C9). Only the SPENDING INPUT
            // differs -- the entire tax/withdrawal/RMD/LTCG year-step below is shared verbatim (no
            // forked tax logic). The chained assignment updates prevSpending in lock-step without
            // adding a statement to this NCSS-capped hot method.
            double spending = prevSpending = TrialSpending.resolveYearSpending(config, y, view.floor(),
                    view.discretionary(), trialStartTotal, prevSpending);
            // A4: tax on this year's base (outside) income is a real obligation every year, not
            // just when income exceeds spending -- fund it from any surplus first; the unfunded
            // remainder drains straight from the pools via the shared tax cascade below
            // (deductTaxFromPoolsGrossedUp), deliberately NOT folded into the spending withdrawal: it
            // must not generate marginal withdrawal tax on the
            // SPENDING side (that would double-count -- this is a separate, already-computed bill).
            // The funding draw itself IS grossed up when it touches traditional (audit C2) exactly
            // like the deterministic engine's extraPoolFundedTax now is. surplusTax[y] is the
            // precomputed full-year tax on this year's taxable base income -- see
            // OptimizationContextBuilder#computeSurplusTax.
            var funding = TrialSpending.resolveSpendingFunding(view.income(), spending, view.surplusTax());
            double withdrawal = funding.withdrawal();

            // Split withdrawal across pools (59.5 rule: taxable only before age 60). aux bundles the
            // remaining per-year array-or-default lookups (dynamic-sequencing ceiling/conversion,
            // LTCG table, rental income) into one call to keep this NCSS-capped hot method lean --
            // regime-aware (task 6): its LTCG/DS/rental reads come from the survivor regime once
            // spliced, the conversion schedule (survivor-invariant) always from the config.
            boolean preAge595 = hasConversions && age < config.earlyAccessAge();
            var aux = resolveYearAuxInputs(config, view.regime(), y);
            var drawn = splitWithdrawal(tp.taxable(), tp.traditionalTotal(),
                    tp.rothTotal(),
                    withdrawal, order, preAge595,
                    aux.dsCeiling(), view.income(), aux.dsConvAmt(), rmdComputed);

            // EXACT incremental tax on the traditional withdrawal (audit C5): the draw stacks on
            // base + interest + the forced RMD + this year's ACTUAL conversion income (T18a-1
            // order) -- taxAt(base+interest+rmd+actualConv+draw) - taxAt(base+interest+rmd+actualConv)
            // -- correctly pricing any bracket crossing within the draw AND the bracket position
            // the interest/RMD/conversion already pushed the year into.
            double withdrawalTax = 0;
            if (hasPools && drawn.traditional() > 0) {
                withdrawalTax = table.incrementalTax(baseWithInterest + rmdForced + actualConv,
                        drawn.traditional());
            }

            // Withdraw from pools. The taxable spending sale realizes a FIFO long-term gain
            // (returned via WithdrawalOutcome.realizedGain()); the secondary taxable sale — the
            // withdrawal-tax payment — sells FIFO to keep the lots in sync and accumulates its gain
            // on TrialPools for this year's LTCG (D5), matching the deterministic MultiPool's
            // tax-funding-sale treatment.
            WithdrawalOutcome outcome = applyTrialWithdrawals(tp, drawn, withdrawalTax, hasPools, table,
                    baseWithInterest + rmdForced + actualConv);

            TrialTaxes.applyEarlyWithdrawalPenalty(tp, outcome.traditionalDrawn(), age, config.earlyAccessAge());

            // The base-income-tax deduction is NOT part of drawn (it drains via
            // deductTaxFromPoolsGrossedUp below, like withdrawalTax), so this metric already measures
            // spending resources only -- identical to its pre-A4 shape.
            double resourcesForSpending = view.income() + drawn.total();
            if (resourcesForSpending < view.floor() - 1e-6) {
                essentialFloorMet = false;
            }

            // Full ordinary stack realized this year, in audit C1's interest -> T18a-1's
            // rmd -> conversion -> spending draw order -- the point every remaining tax bill's
            // funding draw (and the LTCG bracket floor) stacks on.
            double ordinaryStack = baseWithInterest + rmdForced + actualConv + outcome.traditionalDrawn();

            TrialTaxes.settleBaseIncomeTaxAndSurplus(tp, funding.grossSurplus(), funding.fundedFromSurplus(),
                    funding.unfundedBaseTax(), table, ordinaryStack);

            // T18a-3: aux.rentalIncome() threads this year's net rental income into the NIIT Net
            // Investment Income base only (never the LTCG bracket tax) -- zero when no rental array
            // is configured.
            TrialTaxes.applyLtcgTax(tp, outcome.realizedGain(), dividendIncome, aux.ltcgTable(), ordinaryStack, table,
                    aux.rentalIncome());

            tp.clampNonNegative();
            settleTent(tent, tp, expectedAfterGrowth, y);

            double totalBalance = tp.poolTotal();
            minBalance = Math.min(minBalance, totalBalance);
            if (yearBalances != null) {
                yearBalances[y] = totalBalance;
            }

            // Perf safety valve for very long horizons: once the year's lot mutations
            // (growth/dividend/withdrawal/reinvest) are done, merge the oldest lots down to the
            // cap so FIFO gain-sale doesn't degrade with unbounded lot growth. Preserves total
            // value and basis exactly (see TaxableLots#consolidateIfNeeded), so it is a no-op at
            // realistic lot counts (~180 for typical horizons) and never changes trial output.
            tp.consolidateLotsIfNeeded(200);
        }

        double finalBalance = Math.max(0, tp.poolTotal());
        carryForwardBequest(yearBalances, loopYears, years, finalBalance);
        boolean traditionalExhausted = config.conversionByYear() != null
                && tp.traditionalTotal() <= 0;

        double tentYearsCovered = yearsCovered(config.tent());
        return new TrialResult(finalBalance, minBalance, yearBalances, traditionalExhausted, essentialFloorMet,
                tentYearsCovered);
    }

    /** Household task 6: a truncated household trial (loopYears &lt; years) carries its final bequest
     * balance forward across the unsimulated tail so tracked year-balances stay flat rather than
     * collapsing to zero. No-op for a single-person / full-horizon run (loopYears == years) or an
     * untracked pass ({@code yearBalances == null}). */
    private static void carryForwardBequest(@Nullable double[] yearBalances, int loopYears, int years,
                                            double finalBalance) {
        if (yearBalances == null) {
            return;
        }
        for (int y = loopYears; y < years; y++) {
            yearBalances[y] = finalBalance;
        }
    }

    /** The tent when it still holds anything this year, else {@code null} (the untented path). */
    @Nullable
    private static TrialTent activeTent(@Nullable TrialTent tent) {
        return tent == null || tent.isEmpty() ? null : tent;
    }

    /** The tent's coverage count, or {@code NaN} without a tent. */
    private static double yearsCovered(@Nullable TrialTent tent) {
        return tent == null ? Double.NaN : tent.yearsCovered();
    }

    /**
     * Year-end tent settlement, after the pools are floored at zero. Below the growth index's previous high
     * the tent pays the year's net outflow (what the pools would hold after growth, less what they hold now)
     * shortest maturity first; then every pool's reservation is clamped to its balance, and the year's draw is
     * recorded for the coverage count of retired year {@code y}. A no-op without a tent.
     */
    private static void settleTent(@Nullable TrialTent tent, TrialPools tp, double expectedAfterGrowth, int y) {
        if (tent == null) {
            return;
        }
        double before = tent.total();
        if (tent.isBelowPeak()) {
            tent.spend(Math.max(0, expectedAfterGrowth - tp.poolTotal()));
        }
        tent.clampAll(tp.taxable(), tp.traditionalTotal(), tp.rothTotal());
        tent.recordCoverageYear(y, before);
    }

    /** Bundles {@link #simulateTrial}'s remaining per-year array-or-default lookups (dynamic-
     * sequencing bracket ceiling/conversion amount, the LTCG table, and T18a-3's net rental
     * income) into a single call, keeping the NCSS-capped hot method lean. */
    private record YearAuxInputs(double dsCeiling, double dsConvAmt, LtcgTaxTable ltcgTable, double rentalIncome) {}

    /** {@code regime} (task 6) supplies the survivor-phase LTCG/DS-ceiling/rental arrays once spliced
     * ({@code null} ⇒ joint/base phase → read from {@code config}); the Roth conversion schedule is
     * survivor-invariant, so {@code dsConvAmt} always comes from the config. */
    private static YearAuxInputs resolveYearAuxInputs(SimulationConfig config,
                                                      @Nullable SurvivorRegime regime, int y) {
        double[] dsCeilingArr = regime != null ? regime.dsBracketCeilingByYear() : config.dsBracketCeilingByYear();
        double dsCeiling = dsCeilingArr != null ? dsCeilingArr[y] : 0;
        double dsConvAmt = config.conversionByYear() != null ? config.conversionByYear()[y] : 0;
        LtcgTaxTable[] ltcgArr = regime != null ? regime.ltcgTaxTableByYear() : config.ltcgTaxTableByYear();
        LtcgTaxTable ltcgTable = ltcgArr != null ? ltcgArr[y] : null;
        double[] rentalArr = regime != null ? regime.rentalIncomeByYear() : config.rentalIncomeByYear();
        double rentalIncome = rentalArr != null ? rentalArr[y] : 0.0;
        return new YearAuxInputs(dsCeiling, dsConvAmt, ltcgTable, rentalIncome);
    }

    /**
     * Sub-project B (stochastic mortality), task 6: one trial-year's regime-spliced scalar inputs.
     * {@code regime} is {@code null} off the stochastic path / before the transition, in which case
     * every field is today's base-array/config read (the byte-identical anchor); otherwise the income/
     * surplus-tax/tables come from the survivor regime and {@code floor}/{@code discretionary} are
     * scaled by the survivor factor. {@code table}/{@code base} are the year's exact ordinary tax
     * table and stacking base ({@code null}/0 when {@code hasPools} is false).
     */
    private record YearRegimeView(
            @Nullable SurvivorRegime regime, double income, double surplusTax,
            double floor, double discretionary, @Nullable OrdinaryTaxTable table, double base) {}

    /** Resolves one year's {@link YearRegimeView} (task 6). Extracted from {@link #simulateTrial} so
     * the per-year regime/base-array selection branches live here rather than inflating the hot
     * method's NCSS/cognitive-complexity budget. */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable argument
    // list -- varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private static YearRegimeView resolveYearRegimeView(SimulationConfig config,
            @Nullable HouseholdSim household, int y, boolean hasPools,
            double[] income, double[] surplusTax, double[] floors, double[] discretionary) {
        SurvivorRegime regime = regimeAt(config, household, y);
        double factor = regime != null ? config.survivorFactor() : 1.0;
        OrdinaryTaxTable table = null;
        double base = 0.0;
        if (hasPools) {
            table = regime != null ? regime.ordinaryTaxTableByYear()[y] : config.ordinaryTaxTableByYear()[y];
            base = regime != null ? regime.ordinaryBaseIncomeByYear()[y] : config.ordinaryBaseIncomeByYear()[y];
        }
        return new YearRegimeView(regime,
                regime != null ? regime.income()[y] : income[y],
                regime != null ? regime.surplusTax()[y] : surplusTax[y],
                floors[y] * factor, discretionary[y] * factor, table, base);
    }

    /**
     * Sub-project B (stochastic mortality), task 6: this trial-year's spliced survivor regime, or
     * {@code null} when today's base method-argument arrays / config drive the year unchanged — which
     * is EVERY year on the fixed-death / single-person path ({@code config.survivorRegimes() == null},
     * the byte-identical anchor) and every year BEFORE the trial's own first-death transition index on
     * the stochastic path. From the transition index forward it is the regime for this trial's
     * survivor identity, selected by the SAME {@code survivorIsPrimary} flag that drives the pool
     * rollover/step-up direction, so the income/tax regime and the pool mechanics always agree.
     */
    @Nullable
    private static SurvivorRegime regimeAt(SimulationConfig config, @Nullable HouseholdSim household, int y) {
        SurvivorRegime[] regimes = config.survivorRegimes();
        if (regimes == null || household == null || y < household.transitionYearIndex()) {
            return null;
        }
        return regimes[household.survivorIsPrimary() ? PRIMARY_SURVIVES : SPOUSE_SURVIVES];
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
    private static double applyTrialConversion(TrialPools tp, double[] conversionByYear,
                                                double[] conversionTaxByYear, int y, int age,
                                                int earlyAccessAge, OrdinaryTaxTable table,
                                                double base) {
        double tradTotal = tp.traditionalTotal();
        if (conversionByYear == null || conversionByYear[y] <= 0 || tradTotal <= 0) {
            return 0;
        }
        double actualConv = Math.min(conversionByYear[y], tradTotal);
        // Household task 6: debit traditional PROPORTIONALLY by owner balance and credit each owner's
        // OWN Roth by that same per-owner amount (a conversion stays within the owner). Single-person:
        // the whole amount moves TRAD_P → ROTH_P bit-for-bit.
        tp.convertToRoth(actualConv);
        double actualTax;
        if (table != null) {
            actualTax = table.incrementalTax(base, actualConv);
        } else {
            actualTax = (actualConv < conversionByYear[y])
                    ? conversionTaxByYear[y] * (actualConv / conversionByYear[y])
                    : conversionTaxByYear[y];
        }
        if (age < earlyAccessAge) {
            double taxPaid = Math.min(actualTax, Math.max(0, tp.taxable()));
            tp.sellTaxableForTax(taxPaid);   // D5: conversion-tax sale gain taxed with this year's LTCG
        } else {
            tp.deductTaxFromPools(actualTax);
        }
        return actualConv;
    }

    /**
     * Deducts the spending draw and the withdrawal tax from pools. Returns a
     * {@link WithdrawalOutcome} (Task 17 -- replaces the old {@code realizedGainOut}/{@code
     * traditionalDrawnOut} single-element out-arrays the caller allocated fresh every year).
     * {@code traditionalDrawn} is the dollar amount actually debited from traditional for
     * this year's spending draw. {@code base} is the ordinary income already realized BEFORE this
     * call (income-source base + the year's actual Roth conversion); the withdrawal-tax drain's
     * gross-up point additionally stacks the actual traditional spending draw on it.
     * Package-private (mirrors {@link #splitWithdrawal}) so it can be unit tested directly instead
     * of only through the full {@link #simulateTrial} pipeline.
     */
    static WithdrawalOutcome applyTrialWithdrawals(TrialPools tp, PoolWithdrawal drawn,
                                                   double withdrawalTax, boolean hasPools,
                                                   OrdinaryTaxTable table, double base) {
        double realizedGain = tp.sellTaxable(drawn.taxable());
        tp.debitPair(TRAD_P, TRAD_S, drawn.traditional());
        tp.debitPair(ROTH_P, ROTH_S, drawn.roth());
        double traditionalDrawn = drawn.traditional();
        if (hasPools) {
            TrialTaxes.deductTaxFromPoolsGrossedUp(withdrawalTax, tp, table, base + traditionalDrawn);
        }
        return new WithdrawalOutcome(realizedGain, traditionalDrawn);
    }

    /**
     * Split a withdrawal need across three pools using the specified ordering.
     * Returns a {@link PoolWithdrawal} describing how much comes from each pool.
     * When preAge595 is true, only the taxable pool is available (59.5 early withdrawal rule).
     * Every order other than dynamic sequencing is a fixed pool priority, delegated to
     * {@link PoolWithdrawal#greedy}.
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

        if (order == WithdrawalOrder.DYNAMIC_SEQUENCING) {
            // Bracket-driven, not a fixed priority order: traditional first up to the bracket
            // space, then taxable, then Roth.
            double bracketSpace = Math.max(0,
                    dsBracketCeiling - otherIncome - conversionAmount - rmdAmount);
            double fromTrad = Math.min(bracketSpace, Math.min(Math.max(0, traditional), need));
            double remaining = need - fromTrad;
            double fromTax = Math.min(remaining, Math.max(0, taxable));
            remaining -= fromTax;
            double fromRoth = Math.min(remaining, Math.max(0, roth));
            return new PoolWithdrawal(fromTax, fromTrad, fromRoth);
        }

        return PoolWithdrawal.greedy(order, taxable, traditional, roth, need);
    }
}
