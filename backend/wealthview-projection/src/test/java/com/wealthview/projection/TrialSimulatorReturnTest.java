package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies that {@link TrialSimulator} grows each tax pool at its own per-year return sequence
 * (Task 15: per-pool allocation-driven Monte Carlo returns), and that the cash-reserve down-year
 * decision keys on the balance-weighted portfolio return across the three pools.
 */
class TrialSimulatorReturnTest {

    private final TrialSimulator simulator = new TrialSimulator();

    private static TrialSimulator.SimulationConfig config(
            double initTaxable, double initTraditional, double initRoth,
            int cashReserveYears,
            double[] taxableReturns, double[] traditionalReturns, double[] rothReturns) {
        return new TrialSimulator.SimulationConfig(
                initTaxable, initTraditional, initRoth,
                "taxable_first", null, null, null, 62, null,
                cashReserveYears, 0.0, false,
                taxableReturns, traditionalReturns, rothReturns, Integer.MAX_VALUE,
                initTaxable, null, 0.0);
    }

    @Test
    void simulateTrial_perPoolReturns_growEachPoolIndependently() {
        // 1 year, no withdrawals/income; taxable +10%, traditional +2%, roth +8%.
        // 110 + 102 + 108 = 320.
        var config = config(100, 100, 100, 0,
                new double[]{0.10}, new double[]{0.02}, new double[]{0.08});

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(320.0, within(1e-6));
    }

    @Test
    void simulateTrial_perPoolReturns_compoundIndependentlyAcrossYears() {
        // 2 years, no spend/income. taxable 100 -> 110 -> 121; traditional 100 -> 102 -> 104.04;
        // roth 100 -> 108 -> 116.64. Sum = 341.68.
        var config = config(100, 100, 100, 0,
                new double[]{0.10, 0.10}, new double[]{0.02, 0.02}, new double[]{0.08, 0.08});

        var result = simulator.simulateTrial(
                new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0}, 2, config);

        assertThat(result.finalBalance()).isEqualTo(121.0 + 104.04 + 116.64, within(1e-6));
    }

    @Test
    void simulateTrial_cashReserveDownYear_keysOnBalanceWeightedPortfolioReturn() {
        // Taxable pool is UP (+10%) but traditional is sharply DOWN (-30%); the balance-weighted
        // portfolio return is negative, so the down-year branch draws the year's spending from the
        // cash reserve and leaves the equity pools untouched.
        var config = config(100, 100, 0, 1,
                new double[]{0.10}, new double[]{-0.30}, new double[]{0.0});

        // floor spend = 10 -> cash reserve seeded at 10 (drawn from taxable first).
        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{10}, new double[]{0}, 1, config);

        // taxable: (100 - 10 seeded) * 1.10 = 99 ; traditional: 100 * 0.70 = 70 ; cash: 10 - 10 spent = 0.
        assertThat(result.finalBalance()).isEqualTo(99.0 + 70.0, within(1e-6));
    }

    @Test
    void simulateTrial_essentialFloorUnfundableInAYear_marksNotSuccess() {
        var sim = new TrialSimulator();
        // Tiny portfolio, no income, a floor larger than the portfolio can ever supply → shortfall.
        double[] flatNoReturn = {0.0, 0.0};
        var config = new TrialSimulator.SimulationConfig(
                100.0, 0.0, 0.0, "taxable_first", null,
                null, null, 60, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, Integer.MAX_VALUE,
                100.0, null, 0.0);
        double[] income = {0.0, 0.0};
        double[] zero = {0.0, 0.0};
        double[] floors = {80.0, 80.0};        // year 2 floor (80) unfundable: only ~20 left
        double[] discretionary = {0.0, 0.0};

        var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

        assertThat(result.success()).isFalse();
    }

    @Test
    void simulateTrial_floorFundedEveryYear_marksSuccess() {
        var sim = new TrialSimulator();
        double[] flatNoReturn = {0.0, 0.0};
        var config = new TrialSimulator.SimulationConfig(
                1000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 60, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, Integer.MAX_VALUE,
                1000.0, null, 0.0);
        double[] income = {0.0, 0.0};
        double[] zero = {0.0, 0.0};
        double[] floors = {50.0, 50.0};
        double[] discretionary = {0.0, 0.0};

        var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

        assertThat(result.success()).isTrue();
    }

    @Test
    void simulateTrial_taxableWithdrawalWithEmbeddedGain_realizesLtcgTax() {
        // 1 year, no growth, no cash reserve, no income. Taxable pool worth 1000 with a 600 basis
        // (40% embedded gain). Spend 500 -> a FIFO half-lot sale realizes gain 500 - 600*(500/1000)
        // = 200. LTCG rate 0.15 -> tax 30, paid taxable-first. Final = (1000 - 500) - 30 = 470
        // (vs 500 with no LTCG tax: the 30 is exactly the extra cash the capital-gains tax removes).
        double[] flatNoReturn = {0.0};
        var config = new TrialSimulator.SimulationConfig(
                1000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 62, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, Integer.MAX_VALUE,
                600.0, new double[]{0.15}, 0.0);

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{500}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(470.0, within(1e-6));
    }

    @Test
    void simulateTrial_dividendYield_drainsQualifiedDividendTax() {
        // 1 year, taxable +10%, dividend yield 2%, no withdrawal. The pool still grows to 1100
        // (dividend booked as residual), but the 2% dividend (20) is qualified-dividend income taxed
        // at the 0.15 LTCG rate -> 3 leaves the portfolio. Final = 1100 - 3 = 1097 (dividend drag).
        var config = new TrialSimulator.SimulationConfig(
                1000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 62, null, 0, 0.0, false,
                new double[]{0.10}, new double[]{0.0}, new double[]{0.0}, Integer.MAX_VALUE,
                1000.0, new double[]{0.15}, 0.02);

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(1097.0, within(1e-6));
    }

    @Test
    void simulateTrial_rmdAgeReached_forcesTraditionalDistributionToTaxable() {
        // 1 year, no growth, no spending: taxable=0, traditional=100000, roth=0,
        // marginalRateByYear={0.20}, rmdStartAge=75, retirementAge=75 (age year0 = 75).
        // divisor(75)=24.6 -> rmd = 100000 / 24.6 = 4065.0406504065...
        // Nothing is drawn for spending, so extra = rmd (capped at the 100000 traditional balance).
        // taxExtra = rmd * 0.20; pools: traditional = 100000 - rmd; taxable += rmd - taxExtra.
        // finalBalance = (100000 - rmd) + (rmd - taxExtra) = 100000 - taxExtra ~= 99186.99
        // (the tax on the forced distribution is the only amount that leaves the portfolio).
        double[] flatNoReturn = {0.0};
        var config = new TrialSimulator.SimulationConfig(
                0.0, 100_000.0, 0.0, "taxable_first", new double[]{0.20},
                null, null, 75, null, 0, 0.0, false,
                flatNoReturn, flatNoReturn, flatNoReturn, 75,
                0.0, null, 0.0);

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(99186.99, within(0.01));
    }

    // === A1 fix: cash-reserve down-year pool accounting (audit 2026-07-11, item A1) ===

    @Test
    void applyTrialWithdrawals_cashPartiallyCovers_debitsEachPoolByScaledShareAndDeductsFullTax() {
        // Down-year cash reserve partially covers a $600 draw: cash has $200, so equityDraw=$400
        // is scaled across the three theoretical pool draws (taxable=95, traditional=285,
        // roth=220, total=600) at scale=400/600=2/3. Withdrawal tax ($57) is then deducted in
        // FULL via the shared PoolTaxCascade against the post-draw pools (taxable-first,
        // spilling into traditional) -- not the old dimensionally-wrong tax^2/portfolio fraction
        // that left ~99% of the tax unpaid.
        double[] pools = {95.0, 285.0, 285.0};
        TaxableLots lots = new TaxableLots();
        lots.addLot(95.0, 95.0);
        double[] realizedGainOut = {0.0};
        double[] traditionalDrawnOut = {0.0};
        var drawn = new PoolWithdrawal(95.0, 285.0, 220.0);

        // marginalRate=0 isolates the A1 pool-scaling fix from audit C2's gross-up (pinned
        // separately below in applyTrialWithdrawals_taxDrainsTraditional_grossesUpByTMOverOneMinusM).
        double cashAfter = TrialSimulator.applyTrialWithdrawals(pools, lots, realizedGainOut,
                traditionalDrawnOut, 200.0, drawn, 57.0, 600.0, 200.0, true, 1, -0.05, 0.0);

        // Spending draw: taxable -= 95*(2/3)=63.3333 -> 31.6667; traditional -= 285*(2/3)=190 ->
        // 95; roth -= 220*(2/3)=146.6667 -> 138.3333. Tax (57) then cascades taxable-first:
        // taxable 31.6667 -> 0 (draws 31.6667), remainder 25.3333 from traditional -> 69.6667;
        // roth untouched by tax.
        assertThat(pools[0]).isEqualTo(0.0, within(1e-6));
        assertThat(pools[1]).isEqualTo(69.666667, within(1e-4));
        assertThat(pools[2]).isEqualTo(138.333333, within(1e-4));
        assertThat(cashAfter).isEqualTo(0.0, within(1e-6));
        assertThat(traditionalDrawnOut[0]).isEqualTo(190.0, within(1e-6));
    }

    @Test
    void applyTrialWithdrawals_cashFullyCovers_leavesPoolsUntouchedButStillDeductsFullTax() {
        // Cash ($100) fully covers the $100 spending draw -> equityDraw=0, so the theoretical
        // pool split (100/0/0) must NOT be applied to the pools at all -- that is the entire
        // point of the reserve. Withdrawal tax ($30) still leaves the pools in full via the
        // cascade, proving tax is never skipped just because the spending draw was cash-funded
        // (and proving the old "deduct pools twice on full cover" bug is gone).
        double[] pools = {360.0, 180.0, 90.0};
        TaxableLots lots = new TaxableLots();
        lots.addLot(360.0, 360.0);
        double[] realizedGainOut = {0.0};
        double[] traditionalDrawnOut = {0.0};
        var drawn = new PoolWithdrawal(100.0, 0.0, 0.0);

        // marginalRate=0.20 (nonzero, to prove it's IRRELEVANT here): taxable ($360) fully covers
        // the $30 tax, so traditional is never touched and there is nothing to gross up (audit C2).
        double cashAfter = TrialSimulator.applyTrialWithdrawals(pools, lots, realizedGainOut,
                traditionalDrawnOut, 100.0, drawn, 30.0, 100.0, 100.0, true, 1, -0.05, 0.20);

        assertThat(pools[0]).isEqualTo(330.0, within(1e-6));   // 360 - 30 tax only
        assertThat(pools[1]).isEqualTo(180.0, within(1e-6));   // untouched by the spending draw
        assertThat(pools[2]).isEqualTo(90.0, within(1e-6));    // untouched by the spending draw
        assertThat(cashAfter).isEqualTo(0.0, within(1e-6));    // cash drained by the $100 draw
        assertThat(traditionalDrawnOut[0]).isEqualTo(0.0, within(1e-6));
    }

    // === Audit C2: tax paid FROM the traditional pool must gross up (the draw is itself taxable) ===

    @Test
    void applyTrialWithdrawals_taxDrainsTraditional_grossesUpByTMOverOneMinusM() {
        // Taxable is $0, so the $57 withdrawal tax drains entirely from traditional: T=57. At a 20%
        // marginal rate, the closed-form gross-up is T*m/(1-m) = 57*0.20/0.80 = 14.25, drained as a
        // SECOND, separate reduction directly against traditional (not re-cascaded through the
        // taxable-first order) -- total traditional debit for the tax = 57 + 14.25 = 71.25.
        double[] pools = {0.0, 500.0, 0.0};
        TaxableLots lots = new TaxableLots();
        double[] realizedGainOut = {0.0};
        double[] traditionalDrawnOut = {0.0};
        var drawn = new PoolWithdrawal(0.0, 0.0, 0.0); // no spending draw this call -- isolates the tax

        double cashAfter = TrialSimulator.applyTrialWithdrawals(pools, lots, realizedGainOut,
                traditionalDrawnOut, 0.0, drawn, 57.0, 0.0, 0.0, true, 0, 0.05, 0.20);

        assertThat(pools[0]).isEqualTo(0.0, within(1e-9));
        assertThat(pools[1]).isEqualTo(500.0 - 57.0 - 14.25, within(1e-9)); // = 428.75
        assertThat(pools[2]).isEqualTo(0.0, within(1e-9));
        assertThat(cashAfter).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void applyTrialWithdrawals_taxFundedFromTaxable_unaffectedByMarginalRate() {
        // Direction/control pin for the C2 test above: an otherwise-identical trial where taxable
        // covers the SAME $57 tax bill in full -- the marginal rate must be completely irrelevant
        // (no gross-up possible when traditional is never touched by the tax).
        double[] pools = {500.0, 500.0, 0.0};
        TaxableLots lots = new TaxableLots();
        lots.addLot(500.0, 500.0);
        double[] realizedGainOut = {0.0};
        double[] traditionalDrawnOut = {0.0};
        var drawn = new PoolWithdrawal(0.0, 0.0, 0.0);

        double cashAfter = TrialSimulator.applyTrialWithdrawals(pools, lots, realizedGainOut,
                traditionalDrawnOut, 0.0, drawn, 57.0, 0.0, 0.0, true, 0, 0.05, 0.20);

        assertThat(pools[0]).isEqualTo(500.0 - 57.0, within(1e-9)); // = 443.0, taxable-funded, no gross-up
        assertThat(pools[1]).isEqualTo(500.0, within(1e-9));        // traditional untouched
        assertThat(pools[2]).isEqualTo(0.0, within(1e-9));
        assertThat(cashAfter).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void simulateTrial_cashReserveDownYear_partialCoverDebitsAllThreePoolsAndFullWithdrawalTax() {
        // End-to-end version of the direct-call test above: taxable/traditional/roth all start
        // at 300, a 1-year cash reserve seeds $200 out of taxable. A uniform -5% down year plus
        // a deliberately outsized withdrawal need ($600, via a -$400 "other income" against a
        // $200 floor) spills the theoretical draw across all three pools; cash covers $200 of
        // it, leaving a $400 equity draw at marginal rate 20% ($57 tax, of which taxable funds
        // $31.6667 and traditional funds the $25.3333 remainder). Post-draw pool total = 665
        // (post-growth) - 400 (equity draw) - 57 (tax) = 208 pre-C2; audit C2 grosses up the
        // $25.3333 traditional slice of the tax by 25.3333*0.20/0.80 = 6.3333 more, so finalBalance
        // = 208 - 6.3333 = 201.6667, cash fully drained.
        var config = new TrialSimulator.SimulationConfig(
                300.0, 300.0, 300.0, "taxable_first", new double[]{0.20},
                null, null, 62, null, 1, 0.0, false,
                new double[]{-0.05}, new double[]{-0.05}, new double[]{-0.05}, Integer.MAX_VALUE,
                300.0, null, 0.0);

        var result = simulator.simulateTrial(
                new double[]{-400.0}, new double[]{0.0}, new double[]{200.0}, new double[]{0.0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(201.666667, within(1e-4));
    }

    @Test
    void simulateTrial_cashReserveDownYear_taxAwareFullCoverLeavesEquityPoolsUntouched() {
        // Tax-aware (hasPools=true) counterpart of simulateTrial_cashReserveDownYear_
        // keysOnBalanceWeightedPortfolioReturn above: cash ($100, seeded from taxable) fully
        // covers a $100 down-year withdrawal (taxable-first, so the theoretical draw never even
        // reaches traditional/roth) -> the equity pools must be debited NOTHING, exactly like
        // the no-tax final-response path. taxable 500 -> (seed 100) 400 -> (*0.9) 360;
        // traditional 200 -> 180; roth 100 -> 90; cash 100 -> 0 (drained by the draw).
        var config = new TrialSimulator.SimulationConfig(
                500.0, 200.0, 100.0, "taxable_first", new double[]{0.20},
                null, null, 62, null, 1, 0.0, false,
                new double[]{-0.10}, new double[]{-0.10}, new double[]{-0.10}, Integer.MAX_VALUE,
                500.0, null, 0.0);

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{100.0}, new double[]{0.0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(630.0, within(1e-6));
    }

    @Test
    void simulateTrial_rmdForcedInCashCoveredDownYear_paysFullExcessWithTaxLeakage() {
        // Age-75 RMD year, cash reserve fully covers the year's spending draw from a
        // traditional-first ordered portfolio -- the ACTUAL traditional pool draw for spending
        // is therefore zero, not the theoretical $1000 split. forceRmdExcess must still force
        // the FULL rmd out of traditional (not rmd-minus-the-phantom-$1000), with tax leakage at
        // the 20% marginal rate.
        // Seed: annualSpending=1000, cashReserveYears=1 -> cash=1000, drawn from traditional
        // (taxable=0) -> traditional 100000-1000=99000. Growth -5% -> 94050 (pools1PreGrowth=
        // 99000 is the RMD basis). rmd = 99000/24.6 = 4024.390243902439. Withdrawal ($1000) is
        // fully cash-covered (cashDraw=min(1000,1000)=1000, equityDraw=0) -> pools untouched by
        // the spending draw; withdrawal tax 1000*0.20=200 (computed on the theoretical
        // traditional-first split, deducted in full per the fix) -> 94050-200=93850.
        // forceRmdExcess then forces the FULL rmd (traditionalDrawnForSpending=0, so
        // extra=rmd-0=rmd) out of the 93850, reinvesting the after-tax remainder to taxable.
        // Pre-C2: finalBalance = 93850 - rmd*0.20 = 93045.121951... Audit C2: the $200 withdrawal
        // tax itself drains entirely from traditional (taxable is $0), grossing up by
        // 200*0.20/0.80 = 50 more -> finalBalance = 93045.121951... - 50 = 92995.121951...
        // (forceRmdExcess's own leakage is untouched -- it's a direct one-shot computation, not a
        // deductTaxFromPools drain, so it stays out of C2's scope).
        double[] flatZero = {0.0};
        var config = new TrialSimulator.SimulationConfig(
                0.0, 100_000.0, 0.0, "traditional_first", new double[]{0.20},
                null, null, 75, null, 1, 0.0, false,
                flatZero, new double[]{-0.05}, flatZero, 75,
                0.0, null, 0.0);

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{1000.0}, new double[]{0.0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(92995.12, within(0.01));
    }

    // === A4 fix: tax on outside income must be a funded outflow every year, not just surplus years ===

    @Test
    void simulateTrial_pensionHeavyDeficitYears_fundsBaseIncomeTaxEveryYearNotJustSurplusYears() {
        // Pre-fix, surplusTax[y] was only ever applied when income[y] > spending -- a deficit year
        // (income doesn't cover spending) charged $0 tax on the pension, no matter how large
        // surplusTax[y] was. 2 years, no growth, taxable-only portfolio isolates the fix cleanly
        // (no traditional-withdrawal marginal tax to conflate with it). Pension $20,000/yr against
        // $30,000/yr spending is a deficit every year (grossSurplus = -10,000 < 0), so pre-fix this
        // pension's $3,000/yr tax was NEVER charged. Post-fix: the spending withdrawal stays
        // 10,000/yr and the unfunded base tax (3,000/yr, no surplus to fund it from) drains
        // separately via the deductTaxFromPools cascade -- 13,000/yr total outflow; with 0% growth
        // (nothing to compound), 2 years is a flat $6,000 lower final balance than the pre-fix
        // $80,000.
        double[] flatZero = {0.0, 0.0};
        var config = new TrialSimulator.SimulationConfig(
                100_000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 62, null, 0, 0.0, false,
                flatZero, flatZero, flatZero, Integer.MAX_VALUE,
                100_000.0, null, 0.0);

        var result = simulator.simulateTrial(
                new double[]{20_000.0, 20_000.0}, new double[]{3_000.0, 3_000.0},
                new double[]{30_000.0, 30_000.0}, new double[]{0.0, 0.0}, 2, config);

        // Post-fix pinned value: 100,000 - 2*(10,000 spend gap + 3,000 base tax) = 74,000.
        // Pre-fix (RED) this simulated to 80,000 -- the $6,000 of pension tax simply vanished.
        assertThat(result.finalBalance()).isEqualTo(74_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_surplusYearFullyCoversBaseTax_behaviorUnchangedFromPreFix() {
        // Direction check: when the year's surplus comfortably covers the base income tax, the fix
        // must reproduce the pre-fix result exactly -- fund the tax from the surplus (same net
        // deposit as before), no extra pool draw. Income $50,000 vs spending $30,000 -> surplus
        // $20,000, comfortably covers the $3,000 base tax -> net reinvested = 20,000 - 3,000 =
        // 17,000, exactly matching what the pre-fix `netSurplus = max(0, grossSurplus - surplusTax)`
        // formula already produced for this case.
        double[] flatZero = {0.0};
        var config = new TrialSimulator.SimulationConfig(
                100_000.0, 0.0, 0.0, "taxable_first", null,
                null, null, 62, null, 0, 0.0, false,
                flatZero, flatZero, flatZero, Integer.MAX_VALUE,
                100_000.0, null, 0.0);

        var result = simulator.simulateTrial(
                new double[]{50_000.0}, new double[]{3_000.0},
                new double[]{30_000.0}, new double[]{0.0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(117_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_unfundedBaseTax_drainsWithMarginalGrossUp() {
        // Cross-engine parity pin, UPDATED for audit C2 (this test's name/pin predate C2, which
        // closes exactly the gap it used to document -- see git history for the pre-C2 "zero
        // gross-up" version). Every one of these three tax drains (withdrawalTax, then
        // unfundedBaseTax) is now grossed up independently wherever it touches traditional -- this
        // traditional-only portfolio at a 20% marginal rate, with no taxable pool at all, makes
        // every drain touch traditional. Two identical deficit trials (pension $20,000 vs spending
        // $30,000 -> $10,000 draw from traditional, $2,000 marginal tax, grossed up by
        // 2,000*0.20/0.80=500) differing ONLY in surplusTax ($3,000 vs $0):
        //   withoutBaseTax: 100,000 - 10,000 (draw) - 2,000 (marginal tax) - 500 (its gross-up)
        //     = 87,500.
        //   withBaseTax: 87,500 - 3,000 (base tax) - 750 (its OWN independent gross-up,
        //     3,000*0.20/0.80) = 83,750.
        // Difference = 3,750 (the $3,000 base tax plus its $750 gross-up) -- MORE than the raw
        // $3,000 base tax, unlike the pre-C2 pin, because the base-tax funding draw is no longer
        // tax-free.
        double[] flatZero = {0.0};
        var config = new TrialSimulator.SimulationConfig(
                0.0, 100_000.0, 0.0, "traditional_first", new double[]{0.20},
                null, null, 62, null, 0, 0.0, false,
                flatZero, flatZero, flatZero, Integer.MAX_VALUE,
                0.0, null, 0.0);

        var withBaseTax = simulator.simulateTrial(
                new double[]{20_000.0}, new double[]{3_000.0},
                new double[]{30_000.0}, new double[]{0.0}, 1, config);
        var withoutBaseTax = simulator.simulateTrial(
                new double[]{20_000.0}, new double[]{0.0},
                new double[]{30_000.0}, new double[]{0.0}, 1, config);

        assertThat(withoutBaseTax.finalBalance()).isEqualTo(87_500.0, within(1e-6));
        assertThat(withBaseTax.finalBalance()).isEqualTo(83_750.0, within(1e-6));
        assertThat(withoutBaseTax.finalBalance() - withBaseTax.finalBalance())
                .isEqualTo(3_750.0, within(1e-6));
    }
}
