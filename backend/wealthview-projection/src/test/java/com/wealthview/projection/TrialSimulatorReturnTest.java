package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.household.AgeMilestones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies that {@link TrialSimulator} grows each tax pool at its own per-year return sequence
 * (Task 15: per-pool allocation-driven Monte Carlo returns), and the withdrawal-tax mechanics.
 */
class TrialSimulatorReturnTest {

    private final TrialSimulator simulator = new TrialSimulator();

    private static TrialSimulator.SimulationConfig config(
            double initTaxable, double initTraditional, double initRoth,
            double[] taxableReturns, double[] traditionalReturns, double[] rothReturns) {
        return TrialSimulator.SimulationConfig.builder(initTaxable, initTraditional, initRoth, "taxable_first")
                .retirementAge(62)
                .returns(taxableReturns, traditionalReturns, rothReturns)
                .taxableBasis(initTaxable)
                .build();
    }

    @Test
    void simulateTrial_perPoolReturns_growEachPoolIndependently() {
        // 1 year, no withdrawals/income; taxable +10%, traditional +2%, roth +8%.
        // 110 + 102 + 108 = 320.
        var config = config(100, 100, 100,
                new double[]{0.10}, new double[]{0.02}, new double[]{0.08});

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(320.0, within(1e-6));
    }

    @Test
    void simulateTrial_perPoolReturns_compoundIndependentlyAcrossYears() {
        // 2 years, no spend/income. taxable 100 -> 110 -> 121; traditional 100 -> 102 -> 104.04;
        // roth 100 -> 108 -> 116.64. Sum = 341.68.
        var config = config(100, 100, 100,
                new double[]{0.10, 0.10}, new double[]{0.02, 0.02}, new double[]{0.08, 0.08});

        var result = simulator.simulateTrial(
                new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0}, 2, config);

        assertThat(result.finalBalance()).isEqualTo(121.0 + 104.04 + 116.64, within(1e-6));
    }

    @Test
    void simulateTrial_essentialFloorUnfundableInAYear_marksNotSuccess() {
        var sim = new TrialSimulator();
        // Tiny portfolio, no income, a floor larger than the portfolio can ever supply → shortfall.
        double[] flatNoReturn = {0.0, 0.0};
        var config = TrialSimulator.SimulationConfig.builder(100.0, 0.0, 0.0, "taxable_first")
                .retirementAge(60)
                .returns(flatNoReturn, flatNoReturn, flatNoReturn)
                .taxableBasis(100.0)
                .build();
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
        var config = TrialSimulator.SimulationConfig.builder(1000.0, 0.0, 0.0, "taxable_first")
                .retirementAge(60)
                .returns(flatNoReturn, flatNoReturn, flatNoReturn)
                .taxableBasis(1000.0)
                .build();
        double[] income = {0.0, 0.0};
        double[] zero = {0.0, 0.0};
        double[] floors = {50.0, 50.0};
        double[] discretionary = {0.0, 0.0};

        var result = sim.simulateTrial(income, zero, floors, discretionary, 2, config);

        assertThat(result.success()).isTrue();
    }

    @Test
    void simulateTrial_taxableWithdrawalWithEmbeddedGain_realizesLtcgTax() {
        // 1 year, no growth, no income. Taxable pool worth 1000 with a 600 basis
        // (40% embedded gain). Spend 500 -> a FIFO half-lot sale realizes gain 500 - 600*(500/1000)
        // = 200. LTCG rate 0.15 -> tax 30, paid taxable-first. Final = (1000 - 500) - 30 = 470
        // (vs 500 with no LTCG tax: the 30 is exactly the extra cash the capital-gains tax removes).
        // D5 (Phase 1a): the 30 is itself paid by selling the remaining 500 value / 300 basis lot (40%
        // gain), and that gain is taxed at 15% too: T = 30 / (1 - 0.15 * 0.4) = 31.9149 ->
        // final = 500 - 31.9149 = 468.0851 (was 470).
        double[] flatNoReturn = {0.0};
        var config = TrialSimulator.SimulationConfig.builder(1000.0, 0.0, 0.0, "taxable_first")
                .retirementAge(62)
                .returns(flatNoReturn, flatNoReturn, flatNoReturn)
                .taxableBasis(600.0)
                .ltcgTaxTableByYear(new LtcgTaxTable[]{LtcgTaxTable.flat(0.15)})
                .build();

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{500}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(468.0851063829787, within(1e-6));   // D5 (Phase 1a): was 470
    }

    @Test
    void simulateTrial_dividendYield_drainsQualifiedDividendTax() {
        // 1 year, taxable +10%, dividend yield 2%, no withdrawal. The pool still grows to 1100
        // (dividend booked as residual), but the 2% dividend (20) is qualified-dividend income taxed
        // at the 0.15 LTCG rate -> 3 leaves the portfolio. Final = 1100 - 3 = 1097 (dividend drag).
        // D5 (Phase 1a): the 3 is paid by selling the oldest lot (1080 value / 1000 basis, 7.407% gain),
        // taxed at 15% too: 3 / (1 - 0.15 * 80/1080) = 3.0337 -> final 1096.9663 (was 1097).
        var config = TrialSimulator.SimulationConfig.builder(1000.0, 0.0, 0.0, "taxable_first")
                .retirementAge(62)
                .returns(new double[]{0.10}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(1000.0)
                .ltcgTaxTableByYear(new LtcgTaxTable[]{LtcgTaxTable.flat(0.15)})
                .dividendYield(0.02)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(1096.9662921348315, within(1e-6));   // D5 (Phase 1a): was 1097
    }

    // === Audit C1: bond-sleeve interest is split from the qualified-dividend yield and taxed
    // ORDINARY, not LTCG ===

    @Test
    void simulateTrial_interestYield_drainsOrdinaryInterestTaxNotLtcg() {
        // 1 year, taxable +10%, taxableEquityShare 0.6 (60% equity / 40% bond), dividend_yield 0
        // (isolates interest), interest_yield 4%. blendedYield = 0.6*0 + 0.4*0.04 = 0.016 -> lots
        // grow at (0.10-0.016)=0.084 -> 1084; total distribution = 1100 - 1084 = 16, entirely
        // interest (dividendYield is 0). A flat 20% ORDINARY table taxes it: 16*0.20 = 3.20 ->
        // final = 1100 - 3.20 = 1096.80. No LTCG table is wired at all, proving the interest never
        // touches the LTCG path.
        var config = TrialSimulator.SimulationConfig.builder(1000.0, 0.0, 0.0, "taxable_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20)}, new double[]{0.0})
                .retirementAge(62)
                .returns(new double[]{0.10}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(1000.0)
                .interestYield(0.04)
                .taxableEquityShare(0.6)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(1096.80, within(1e-6));
    }

    @Test
    void simulateTrial_interestYieldTaxableEquityShareDefault_isOneAndByteIdenticalToPreC1() {
        // Back-compat anchor: a SimulationConfig built without naming interestYield/
        // taxableEquityShare must default taxableEquityShare to 1.0 (ALL_US
        // equivalent) -- bit-identical to simulateTrial_dividendYield_drainsQualifiedDividendTax
        // above, even though this fixture doesn't pass interestYield/taxableEquityShare at all.
        var config = TrialSimulator.SimulationConfig.builder(1000.0, 0.0, 0.0, "taxable_first")
                .retirementAge(62)
                .returns(new double[]{0.10}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(1000.0)
                .ltcgTaxTableByYear(new LtcgTaxTable[]{LtcgTaxTable.flat(0.15)})
                .dividendYield(0.02)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(1096.9662921348315, within(1e-6));   // D5 (Phase 1a): was 1097
    }

    @Test
    void simulateTrial_interestIncome_scalesWithTrialPathBalance() {
        // "Path-dependent balance" pin: two trials differing ONLY in their starting taxable
        // balance (proxy for two different Monte Carlo paths arriving at this year with different
        // wealth) must realize proportionally different interest income and therefore different
        // ordinary tax -- interest is computed IN-LOOP from the trial's own balance, not a
        // precomputed schedule. No growth (taxableReturn 0) isolates the interest distribution:
        // blendedYield = 0.4 * 0.04 = 0.016 (60/40 split, dividendYield 0) -> distribution =
        // balance * 0.016 (residual booking against zero growth), taxed at a flat 25% ordinary rate.
        var configSmallBalance = TrialSimulator.SimulationConfig.builder(100_000.0, 0.0, 0.0, "taxable_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.25)}, new double[]{0.0})
                .retirementAge(62)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(100_000.0)
                .interestYield(0.04)
                .taxableEquityShare(0.6)
                .build();
        var configLargeBalance = TrialSimulator.SimulationConfig.builder(500_000.0, 0.0, 0.0, "taxable_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.25)}, new double[]{0.0})
                .retirementAge(62)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(500_000.0)
                .interestYield(0.04)
                .taxableEquityShare(0.6)
                .build();

        var resultSmall = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, configSmallBalance);
        var resultLarge = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, configLargeBalance);

        // interest = balance * 0.016; tax = interest * 0.25 -> net drag = balance * 0.004.
        assertThat(resultSmall.finalBalance()).isEqualTo(100_000.0 * (1 - 0.004), within(1e-6));
        assertThat(resultLarge.finalBalance()).isEqualTo(500_000.0 * (1 - 0.004), within(1e-6));
        // The two trials' interest-driven drag scales with balance, not a fixed dollar amount.
        double dragSmall = 100_000.0 - resultSmall.finalBalance();
        double dragLarge = 500_000.0 - resultLarge.finalBalance();
        assertThat(dragLarge).isEqualTo(dragSmall * 5, within(1e-6));
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
        var config = TrialSimulator.SimulationConfig.builder(0.0, 100_000.0, 0.0, "taxable_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20)}, new double[]{0.0})
                .retirementAge(75)
                .returns(flatNoReturn, flatNoReturn, flatNoReturn)
                .rmdStartAge(75)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, 1, config);

        assertThat(result.finalBalance()).isEqualTo(99186.99, within(0.01));
    }

    // === Audit C2: tax paid FROM the traditional pool must gross up (the draw is itself taxable) ===

    @Test
    void applyTrialWithdrawals_taxDrainsTraditional_grossesUpByTMOverOneMinusM() {
        // Taxable is $0, so the $57 withdrawal tax drains entirely from traditional: T=57. At a 20%
        // marginal rate, the closed-form gross-up is T*m/(1-m) = 57*0.20/0.80 = 14.25, drained as a
        // SECOND, separate reduction directly against traditional (not re-cascaded through the
        // taxable-first order) -- total traditional debit for the tax = 57 + 14.25 = 71.25.
        double[] pools = {0.0, 500.0, 0.0, 0.0, 0.0};   // household task 6: double[5], spouse slots 0
        TaxableLots lots = new TaxableLots();
        TrialPools tp = new TrialPools(pools, lots);
        var drawn = new PoolWithdrawal(0.0, 0.0, 0.0); // no spending draw this call -- isolates the tax

        TrialSimulator.applyTrialWithdrawals(tp,
                drawn, 57.0, true, OrdinaryTaxTable.flat(0.20), 0.0);

        assertThat(pools[0]).isEqualTo(0.0, within(1e-9));
        assertThat(pools[1] + pools[2]).isEqualTo(500.0 - 57.0 - 14.25, within(1e-9)); // trad = 428.75
        assertThat(pools[3] + pools[4]).isEqualTo(0.0, within(1e-9));                  // roth
    }

    @Test
    void applyTrialWithdrawals_taxFundedFromTaxable_unaffectedByMarginalRate() {
        // Direction/control pin for the C2 test above: an otherwise-identical trial where taxable
        // covers the SAME $57 tax bill in full -- the marginal rate must be completely irrelevant
        // (no gross-up possible when traditional is never touched by the tax).
        double[] pools = {500.0, 500.0, 0.0, 0.0, 0.0};   // household task 6: double[5], spouse slots 0
        TaxableLots lots = new TaxableLots();
        lots.addLot(500.0, 500.0);
        TrialPools tp = new TrialPools(pools, lots);
        var drawn = new PoolWithdrawal(0.0, 0.0, 0.0);

        TrialSimulator.applyTrialWithdrawals(tp,
                drawn, 57.0, true, OrdinaryTaxTable.flat(0.20), 0.0);

        assertThat(pools[0]).isEqualTo(500.0 - 57.0, within(1e-9)); // = 443.0, taxable-funded, no gross-up
        assertThat(pools[1] + pools[2]).isEqualTo(500.0, within(1e-9)); // traditional untouched
        assertThat(pools[3] + pools[4]).isEqualTo(0.0, within(1e-9));   // roth
    }

    // === Audit C5: gross-up "m" is the EXACT rate at the post-draw point (base + T), not a flat
    // chord -- distinct from every fixture above, which deliberately used a flat single-bracket
    // table (where the post-draw point can never matter, by linearity). This one uses a REAL
    // two-bracket table so the post-draw point crosses a boundary the base point alone does not. ===

    @Test
    void applyTrialWithdrawals_grossUpRateReadAtPostDrawPoint_crossesBracketBaseAloneDoesNot() {
        // Table: [0,100) @10%, [100,inf) @30%, no deduction. Taxable is $0, so the whole $57 tax
        // bill drains from traditional: T=57. base=90 sits in the 10% bracket, but base+T=147
        // crosses into the 30% bracket -- the gross-up rate MUST be read at base+T (30%), not at
        // base's own bracket (10%): m=0.30, grossUp = 57*0.30/0.70 = 24.428571428571427, total
        // traditional debit = 57 + 24.428571428571427 = 81.428571428571427.
        var table = new OrdinaryTaxTable(0,
                new double[]{0, 100}, new double[]{0.10, 0.30}, new double[]{0, 10});
        double base = 90.0;
        double[] pools = {0.0, 500.0, 0.0, 0.0, 0.0};   // household task 6: double[5], spouse slots 0
        TaxableLots lots = new TaxableLots();
        TrialPools tp = new TrialPools(pools, lots);
        var drawn = new PoolWithdrawal(0.0, 0.0, 0.0);

        TrialSimulator.applyTrialWithdrawals(tp,
                drawn, 57.0, true, table, base);

        double expectedGrossUp = 57.0 * 0.30 / 0.70;
        double traditional = pools[1] + pools[2];
        assertThat(traditional).isEqualTo(500.0 - 57.0 - expectedGrossUp, within(1e-9)); // 418.5714286

        // Direction proof: a (wrong) naive gross-up using the rate AT base's own bracket (10%,
        // sourced from table.rateAt(base) rather than table.rateAt(base + T)) would leave
        // MEANINGFULLY more in the pool -- confirming the post-draw-point read is not a no-op vs.
        // the "obvious" alternative of reusing the pre-draw bracket.
        double naiveGrossUp = 57.0 * 0.10 / 0.90;
        double naiveResultingPool = 500.0 - 57.0 - naiveGrossUp;
        assertThat(traditional).isLessThan(naiveResultingPool - 1.0);
        assertThat(table.rateAt(base)).isEqualTo(0.10, within(1e-9));      // pre-draw bracket
        assertThat(table.rateAt(base + 57.0)).isEqualTo(0.30, within(1e-9)); // actual post-draw point
    }

    // === Audit C5 review fix: the ordinary tax BASE must stack across the year's own income
    // events -- conversion first, then the traditional spending draw, then the forced RMD excess.
    // Pre-fix, every pricing call stacked on the STATIC income-source base alone, so a year with a
    // large conversion priced its traditional draw in the base's (lower) bracket. ===

    /** Two real brackets, no deduction: [0, 100k) @ 10%, [100k, inf) @ 30%. */
    private static OrdinaryTaxTable twoBracketTable() {
        return new OrdinaryTaxTable(0,
                new double[]{0, 100_000}, new double[]{0.10, 0.30}, new double[]{0, 10_000});
    }

    @Test
    void simulateTrial_conversionIncomeStacksUnderWithdrawalTax_pricesDrawInUpperBracket() {
        // base 10k + conversion 80k puts the year's ordinary income at 90k BEFORE the 30k
        // traditional spending draw -- the draw spans 90k..120k, crossing the 100k boundary:
        //   exact = taxAt(120k) - taxAt(90k) = 16,000 - 9,000 = 7,000.
        // Pre-fix (RED) the draw was priced on the static base alone: taxAt(40k) - taxAt(10k) =
        // 3,000 -- the conversion's 80k of ordinary income was invisible to the withdrawal tax.
        // conversionTaxByYear is supplied as the EXACT table figure for the uncapped conversion
        // (taxAt(90k) - taxAt(10k) = 8,000) so the conversion-tax repricing is a no-op here and the
        // pin isolates the withdrawal-tax stacking bug.
        var config = TrialSimulator.SimulationConfig.builder(500_000.0, 200_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{twoBracketTable()}, new double[]{10_000.0})
                .conversions(new double[]{80_000.0}, new double[]{8_000.0})
                .retirementAge(62)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(500_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{30_000.0}, new double[]{0.0}, 1, config);

        // taxable 500k - 8k conv tax - 7k wd tax = 485k; traditional 200k - 80k conv - 30k draw
        // = 90k; roth 80k. Final = 655,000 (pre-fix: 659,000 -- 4,000 of bracket-crossing tax
        // vanished).
        assertThat(result.finalBalance()).isEqualTo(655_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_fullOrdinaryStack_summedTaxEqualsTelescopedTaxAtTotalMinusTaxAtBase() {
        // COMPOSITION IDENTITY (pins the stacking order permanently): with base income, a forced
        // RMD, a Roth conversion, and a traditional spending draw ALL live in one year, the
        // summed ordinary tax must telescope EXACTLY:
        //   rmdTax + convTax + wdTax = taxAt(base+rmd+conv+draw) - taxAt(base)
        // because each component is priced incrementally on top of everything realized before it.
        // T18a-1: the RMD is now forced out BEFORE the conversion (IRS ordering: the year's RMD
        // must be distributed before any Roth conversion) -- the coherent stacking order is rmd
        // on base; conv on base+rmd; draw on base+rmd+conv. (Pre-T18a-1 the order was conv on
        // base; draw on base+conv; excess=[rmd-draw] on base+conv+draw -- the draw/excess split
        // canceled to the SAME base+conv+rmd total, since the spend draw's traditional pull
        // partially "satisfied" the RMD. Under RMD-first ordering the FULL rmd is
        // force-distributed unconditionally, independent of what the spend draw later does, so
        // the draw is no longer netted against it -- the total realized ordinary income is
        // therefore base+rmd+conv+draw, a full $5,000 (the draw) MORE than before.)
        // Fixture: base 60k, rmd = 200k/24.6 (forced first, fully in 10%), conv 50k (crosses 100k
        // mid-conversion), draw 5k (fully in 30%). conversionTaxByYear is supplied as 0
        // (deliberately WRONG) to prove the engine reprices the conversion from the table rather
        // than trusting the precomputed figure.
        var table = twoBracketTable();
        var config = TrialSimulator.SimulationConfig.builder(500_000.0, 200_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{table}, new double[]{60_000.0})
                .conversions(new double[]{50_000.0}, new double[]{0.0})
                .retirementAge(75)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(500_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{5_000.0}, new double[]{0.0}, 1, config);

        // rmd basis = pre-growth traditional 200k -> rmd = 200,000 / 24.6, forced out FIRST and
        // in full (no netting against the later spending draw).
        double rmd = 200_000.0 / 24.6;
        double telescopedTax = table.taxAt(60_000.0 + rmd + 50_000.0 + 5_000.0) - table.taxAt(60_000.0);
        // Portfolio outflows this year = the 5,000 actually spent + every ordinary tax dollar
        // (conversion and the forced RMD move money internally, net of their own tax).
        double initTotal = 500_000.0 + 200_000.0;
        assertThat(initTotal - result.finalBalance())
                .isEqualTo(5_000.0 + telescopedTax, within(1e-6));
        // Sanity on the magnitude: the identity total is dominated by the 30%-bracket segments
        // (conv crosses 100k; the draw sits entirely above it).
        assertThat(telescopedTax).isEqualTo(10_939.02439024, within(1e-4));
    }

    @Test
    void simulateTrial_fullOrdinaryStackWithInterest_interestPricedFirstTelescopesToNewTotal() {
        // Audit C1: same fixture as simulateTrial_fullOrdinaryStack... above, PLUS a 60/40
        // us_stock/bond taxable account realizing $8,000 of ordinary interest this year (500,000 *
        // 0.4 bondShare * 0.04 interestYield -- taxableReturn stays 0, so this is a pure yield-split
        // RECLASSIFICATION with zero net change to pools[0], exactly like the deterministic
        // engine's applyGrowth() at r=0: the residual-booking invariant holds regardless of growth).
        // The new stacking order is base -> INTEREST (priced first, immediately, before RMD/
        // conversion/draw) -> rmd -> conv -> draw -- interest is realized as soon as growth runs,
        // before any of the later withdrawal-cycle machinery. The SUM still telescopes to
        // taxAt(base+interest+rmd+conv+draw) - taxAt(base): total is order-invariant even though
        // the interest tax is now attributed to its own separate pricing call.
        var table = twoBracketTable();
        var config = TrialSimulator.SimulationConfig.builder(500_000.0, 200_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{table}, new double[]{60_000.0})
                .conversions(new double[]{50_000.0}, new double[]{0.0})
                .retirementAge(75)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(500_000.0)
                .interestYield(0.04)
                .taxableEquityShare(0.6)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{5_000.0}, new double[]{0.0}, 1, config);

        double rmd = 200_000.0 / 24.6;
        double interest = 8_000.0;
        double telescopedTax = table.taxAt(60_000.0 + interest + rmd + 50_000.0 + 5_000.0)
                - table.taxAt(60_000.0);
        double initTotal = 500_000.0 + 200_000.0;
        assertThat(initTotal - result.finalBalance())
                .isEqualTo(5_000.0 + telescopedTax, within(1e-6));
        // Sanity on the magnitude: $2,400 (8,000 * 30%) more than the no-interest pin above, since
        // the interest is entirely realized in a year where the stack already crosses into the
        // 30% bracket well before the interest's own slice is added.
        assertThat(telescopedTax).isEqualTo(13_339.02439024, within(1e-4));
    }

    @Test
    void simulateTrial_conversionCappedByTraditional_pricesActualAmountNotProRataTarget() {
        // Target conversion 120k against only 60k of traditional: actualConv = 60k. Pre-fix the
        // trial scaled the precomputed target tax pro-rata (18,000 * 60/120 = 9,000) -- a linear
        // scaling of a CONVEX tax that overstates the capped amount's true tax. Exact:
        // taxAt(10k + 60k) - taxAt(10k) = 7,000 - 1,000 = 6,000 (the target's 18,000 included
        // 30%-bracket dollars the capped conversion never reaches).
        var config = TrialSimulator.SimulationConfig.builder(500_000.0, 60_000.0, 0.0, "taxable_first")
                .taxTables(new OrdinaryTaxTable[]{twoBracketTable()}, new double[]{10_000.0})
                .conversions(new double[]{120_000.0}, new double[]{18_000.0})
                .retirementAge(62)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .taxableBasis(500_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, 1, config);

        // taxable 500k - 6k = 494k; traditional 0; roth 60k. Final = 554,000 (pre-fix: 551,000).
        assertThat(result.finalBalance()).isEqualTo(554_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_rmdExcessRaisesLtcgStackingFloor_flipsGainFromZeroToFifteenPercent() {
        // LTCG floor must include the forced RMD excess (review fix): base 40k alone leaves a 5k
        // realized gain inside the 0% LTCG bracket (floor 40k..45k < 50k ceiling), but the year's
        // forced RMD excess (300k/24.6 = 12,195.12) lifts the ordinary floor to 52,195.12 -- the
        // whole gain now prices at 15% = 750. Pre-fix (RED) the floor omitted the excess -> 0.
        // Ordinary side uses a flat 10% table (linear, so the excess's own ordinary tax is
        // identical pre/post-fix -- the pin isolates the LTCG floor change).
        var ltcgTable = new LtcgTaxTable(0,
                new double[]{0, 50_000}, new double[]{50_000, Double.POSITIVE_INFINITY},
                new double[]{0.0, 0.15}, Double.POSITIVE_INFINITY);
        var config = TrialSimulator.SimulationConfig.builder(100_000.0, 300_000.0, 0.0, "taxable_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{40_000.0})
                .retirementAge(75)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .ltcgTaxTableByYear(new LtcgTaxTable[]{ltcgTable})
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1, config);

        // taxable 100k (basis 50k): 10k spending sale realizes 5k gain -> 90k. RMD excess
        // 12,195.121951 leaves traditional (300k -> 287,804.878049); its 10% ordinary tax
        // (1,219.512195) leaks, net 10,975.609756 reinvested -> taxable 100,975.609756. LTCG 750
        // drains taxable -> 100,225.609756. Final = 100,225.609756 + 287,804.878049 =
        // 388,030.487805 (pre-fix: 388,780.487805, the 750 LTCG never charged).
        // D5 (Phase 1a): the 750 is paid by selling the oldest lot (90k value / 45k basis, 50% gain), whose
        // gain is taxed at 15% too: T = 750 / (1 - 0.15 * 0.5) = 810.81 -> 387,969.676994 (was 388,030.487805).
        assertThat(result.finalBalance()).isEqualTo(387_969.676994, within(1e-4));
    }

    // === T18a-4: 10% IRC 72(t) early-withdrawal penalty on pre-59½ traditional distributions ===

    private static TrialSimulator.SimulationConfig penaltyTestConfig(int retirementAge) {
        // Taxable ($50,000) comfortably funds the ordinary tax AND the penalty -- isolates the
        // penalty from the C2 gross-up fixed point. Traditional-first order draws the $10,000
        // spend need entirely from traditional. rmdStartAge (75) is far above either retirement
        // age tested, so no RMD ever enters the picture.
        return TrialSimulator.SimulationConfig.builder(50_000.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{0.0})
                .retirementAge(retirementAge)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .build();
    }

    @Test
    void simulateTrial_traditionalDrawBeforeAge60_appliesTenPercentPenalty() {
        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1,
                penaltyTestConfig(55));

        // Ordinary tax: 10,000 * 10% = 1,000 (taxable-funded, no gross-up). Penalty: the actual
        // traditional draw (10,000) * 10% = 1,000, also taxable-funded. Taxable: 50,000 - 1,000 -
        // 1,000 = 48,000; traditional: 100,000 - 10,000 = 90,000. Final = 138,000.
        assertThat(result.finalBalance()).isEqualTo(138_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_traditionalDrawAtAge60_noPenaltyAppliesExactlyOneThousandMoreThanBelow60() {
        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1,
                penaltyTestConfig(AgeMilestones.LEGACY_EARLY_ACCESS_AGE));

        // Same fixture, retirement (and thus the trial's only) age is exactly the proxy threshold
        // -- no penalty. Only the $1,000 ordinary tax leaves the portfolio: final = 139,000, i.e.
        // exactly $1,000 (one penalty's worth) more than the below-60 case above.
        assertThat(result.finalBalance()).isEqualTo(139_000.0, within(1e-6));
    }

    // Phase 1a: SimulationConfig.earlyAccessAge (from birth month) replaces the age-60 proxy.

    @Test
    void simulateTrial_age59WithEarlyAccessAge59_noPenalty() {
        var config = TrialSimulator.SimulationConfig.builder(50_000.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{0.0})
                .retirementAge(59)
                .earlyAccessAge(59)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1, config);

        // Only the $1,000 ordinary tax leaves: 139,000 (same as the at-60 case).
        assertThat(result.finalBalance()).isEqualTo(139_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_age59WithDefaultEarlyAccessAge_appliesPenalty() {
        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1,
                penaltyTestConfig(59));

        assertThat(result.finalBalance()).isEqualTo(138_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_conversionScheduleAge59WithEarlyAccessAge59_mayDrawTraditional() {
        // A non-null (zero) conversion schedule switches on the pre-59 1/2 taxable-only rule. With
        // earlyAccessAge 59 the age-59 year is past it, so traditional_first draws traditional.
        var config = TrialSimulator.SimulationConfig.builder(50_000.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{0.0})
                .conversions(new double[]{0.0}, new double[]{0.0})
                .retirementAge(59)
                .earlyAccessAge(59)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1, config);

        // Traditional pays 10,000 + 1,000 tax funded from taxable: 49,000 + 90,000 = 139,000.
        assertThat(result.finalBalance()).isEqualTo(139_000.0, within(1e-6));
    }

    @Test
    void simulateTrial_conversionScheduleAge59WithDefaultEarlyAccessAge_drawsTaxableOnly() {
        var config = TrialSimulator.SimulationConfig.builder(50_000.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.10)}, new double[]{0.0})
                .conversions(new double[]{0.0}, new double[]{0.0})
                .retirementAge(59)
                .returns(new double[]{0.0}, new double[]{0.0}, new double[]{0.0})
                .rmdStartAge(75)
                .taxableBasis(50_000.0)
                .build();

        var result = simulator.simulateTrial(
                new double[]{0.0}, new double[]{0.0}, new double[]{10_000.0}, new double[]{0.0}, 1, config);

        // Taxable-only at basis = value: no gain, no ordinary tax, no penalty -> 140,000.
        assertThat(result.finalBalance()).isEqualTo(140_000.0, within(1e-6));
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
        var config = TrialSimulator.SimulationConfig.builder(100_000.0, 0.0, 0.0, "taxable_first")
                .retirementAge(62)
                .returns(flatZero, flatZero, flatZero)
                .taxableBasis(100_000.0)
                .build();

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
        var config = TrialSimulator.SimulationConfig.builder(100_000.0, 0.0, 0.0, "taxable_first")
                .retirementAge(62)
                .returns(flatZero, flatZero, flatZero)
                .taxableBasis(100_000.0)
                .build();

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
        var config = TrialSimulator.SimulationConfig.builder(0.0, 100_000.0, 0.0, "traditional_first")
                .taxTables(new OrdinaryTaxTable[]{OrdinaryTaxTable.flat(0.20)}, new double[]{0.0})
                .retirementAge(62)
                .returns(flatZero, flatZero, flatZero)
                .build();

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
