package com.wealthview.projection;

import org.springframework.lang.Nullable;

/**
 * Assembles each trial's {@link TrialSimulator.SimulationConfig} from the knobs that stay fixed
 * for one Monte Carlo pass (all {@code trialCount} trials sharing the same pool setup, tax
 * tables, conversion schedule, and return-path arrays) plus the few knobs that are the genuine
 * per-call deltas: the adaptation rule, whether to track per-year balances, and (for {@link
 * StochasticMortalityEvaluator}'s per-trial spliced household) a household override.
 *
 * <p>Task 12 (pattern-refactor): generalizes the three near-identical ~17-line {@code
 * SimulationConfig.builder(...)} chains from {@code GuardrailResponseBuilder.buildSimConfig},
 * {@code SustainabilitySearch.runTrials}, and {@code StochasticMortalityEvaluator.evaluate} into
 * ONE assembly point. Every GATING decision (which array is null when pools are off, which
 * {@code initTaxable}/{@code order} formula applies) stays exactly where and how it already was --
 * at each caller, before it builds this factory -- so {@link #configFor} only ever plugs in
 * ALREADY-resolved values; it introduces no new conditional logic beyond the one Search-only
 * per-trial taxable fallback documented on {@link Builder#initialPortfolioPaths}. See the task-12
 * report for the full per-field asymmetry table across the three original chains.
 */
final class TrialConfigFactory {

    private final PoolSimSetup poolSetup;
    private final double[][] initialPortfolioPaths;
    private final OrdinaryTaxTable[] ordinaryTaxTableByYear;
    private final double[] ordinaryBaseIncomeByYear;
    private final double[] conversionByYear;
    private final double[] conversionTaxByYear;
    private final int retirementAge;
    private final int rmdStartAge;
    private final double[] dsBracketCeilingByYear;
    private final int cashReserveYears;
    private final double cashReturnRate;
    private final double[][] taxableReturns;
    private final double[][] traditionalReturns;
    private final double[][] rothReturns;
    private final double initTaxableBasis;
    private final LtcgTaxTable[] ltcgTaxTableByYear;
    private final double dividendYield;
    private final double[] rentalIncomeByYear;
    private final double interestYield;
    private final double taxableEquityShare;
    private final TrialSimulator.HouseholdSim household;
    private final TrialSimulator.SurvivorRegime[] survivorRegimes;
    private final double survivorFactor;

    private TrialConfigFactory(Builder b) {
        this.poolSetup = b.poolSetup;
        this.initialPortfolioPaths = b.initialPortfolioPaths;
        this.ordinaryTaxTableByYear = b.ordinaryTaxTableByYear;
        this.ordinaryBaseIncomeByYear = b.ordinaryBaseIncomeByYear;
        this.conversionByYear = b.conversionByYear;
        this.conversionTaxByYear = b.conversionTaxByYear;
        this.retirementAge = b.retirementAge;
        this.rmdStartAge = b.rmdStartAge;
        this.dsBracketCeilingByYear = b.dsBracketCeilingByYear;
        this.cashReserveYears = b.cashReserveYears;
        this.cashReturnRate = b.cashReturnRate;
        this.taxableReturns = b.taxableReturns;
        this.traditionalReturns = b.traditionalReturns;
        this.rothReturns = b.rothReturns;
        this.initTaxableBasis = b.initTaxableBasis;
        this.ltcgTaxTableByYear = b.ltcgTaxTableByYear;
        this.dividendYield = b.dividendYield;
        this.rentalIncomeByYear = b.rentalIncomeByYear;
        this.interestYield = b.interestYield;
        this.taxableEquityShare = b.taxableEquityShare;
        this.household = b.household;
        this.survivorRegimes = b.survivorRegimes;
        this.survivorFactor = b.survivorFactor;
    }

    static Builder builder(PoolSimSetup poolSetup) {
        return new Builder(poolSetup);
    }

    /**
     * Assembles trial {@code t}'s {@link TrialSimulator.SimulationConfig}.
     *
     * <p>{@code trackYearBalances} is always threaded through explicitly, so a caller that never
     * varies it (mirroring {@link StochasticMortalityEvaluator}'s original chain, which never
     * called {@code .trackYearBalances(...)} at all) simply always passes {@code false} -- the
     * same value the builder's own default produces, so the built config is identical either way.
     *
     * <p>{@code householdOverride}, when non-null, replaces this factory's baked-in {@code
     * household} for JUST this trial -- the mechanism {@link StochasticMortalityEvaluator} uses to
     * splice in each trial's own sampled first/second death. Every other caller passes {@code
     * null} and every trial uses the one household fixed for the whole pass.
     */
    TrialSimulator.SimulationConfig configFor(int t, @Nullable TrialSimulator.GuardrailAdaptation adaptation,
                                              boolean trackYearBalances,
                                              @Nullable TrialSimulator.HouseholdSim householdOverride) {
        double initTaxable = poolSetup.simPools() || initialPortfolioPaths == null
                ? poolSetup.initTaxable() : initialPortfolioPaths[t][0];
        return TrialSimulator.SimulationConfig
                .builder(initTaxable, poolSetup.initTraditional(), poolSetup.initRoth(), poolSetup.order())
                .taxTables(ordinaryTaxTableByYear, ordinaryBaseIncomeByYear)
                .conversions(conversionByYear, conversionTaxByYear)
                .retirementAge(retirementAge)
                .rmdStartAge(rmdStartAge)
                .dsBracketCeilingByYear(dsBracketCeilingByYear)
                .cashReserve(cashReserveYears, cashReturnRate)
                .trackYearBalances(trackYearBalances)
                .returns(taxableReturns[t], traditionalReturns[t], rothReturns[t])
                .taxableBasis(initTaxableBasis)
                .ltcgTaxTableByYear(ltcgTaxTableByYear)
                .dividendYield(dividendYield)
                .adaptation(adaptation)
                .rentalIncomeByYear(rentalIncomeByYear)
                .interestYield(interestYield)
                .taxableEquityShare(taxableEquityShare)
                .household(householdOverride != null ? householdOverride : household)
                .survivorRegimes(survivorRegimes, survivorFactor)
                .build();
    }

    /**
     * Fluent builder -- one field per {@link TrialSimulator.SimulationConfig} knob this factory
     * caches for the whole pass, mirroring {@link TrialSimulator.SimulationConfig.Builder}'s own
     * shape and its suppressions' justifications: splitting this builder would defeat its purpose
     * of being the ONE place each of the three call sites states its knobs (TooManyFields); the
     * per-year arrays are engine-internal schedules shared by design across trials, same contract
     * as the fields they feed, so a defensive copy per pass would be pure waste
     * (ArrayIsStoredDirectly); every array parameter is a per-year indexed schedule, not a
     * variable argument list (UseVarargs).
     */
    @SuppressWarnings({"PMD.TooManyFields", "PMD.ArrayIsStoredDirectly", "PMD.UseVarargs"})
    static final class Builder {
        private final PoolSimSetup poolSetup;
        private double[][] initialPortfolioPaths;
        private OrdinaryTaxTable[] ordinaryTaxTableByYear;
        private double[] ordinaryBaseIncomeByYear;
        private double[] conversionByYear;
        private double[] conversionTaxByYear;
        private int retirementAge;
        private int rmdStartAge = Integer.MAX_VALUE;
        private double[] dsBracketCeilingByYear;
        private int cashReserveYears;
        private double cashReturnRate;
        private double[][] taxableReturns;
        private double[][] traditionalReturns;
        private double[][] rothReturns;
        private double initTaxableBasis;
        private LtcgTaxTable[] ltcgTaxTableByYear;
        private double dividendYield;
        private double[] rentalIncomeByYear;
        private double interestYield;
        private double taxableEquityShare = 1.0;
        private TrialSimulator.HouseholdSim household;
        private TrialSimulator.SurvivorRegime[] survivorRegimes;
        private double survivorFactor = 1.0;

        private Builder(PoolSimSetup poolSetup) {
            this.poolSetup = poolSetup;
        }

        /**
         * Search-only: the per-trial portfolio paths used as {@code initTaxable}'s fallback
         * ({@code paths[t][0]}) when {@link PoolSimSetup#simPools()} is false. {@code
         * SustainabilitySearch.runTrials}'s non-pool case seeds each trial from its OWN path
         * start rather than a single scalar -- unlike {@link GuardrailResponseBuilder} / {@link
         * StochasticMortalityEvaluator}, whose non-pool {@code initTaxable} is already the single
         * resolved {@code PortfolioSetup.initialPortfolio()} scalar baked into {@code poolSetup}
         * itself, so those two never call this setter (left {@code null}).
         */
        Builder initialPortfolioPaths(double[][] paths) {
            this.initialPortfolioPaths = paths;
            return this;
        }

        Builder taxTables(OrdinaryTaxTable[] tablesByYear, double[] baseIncomeByYear) {
            this.ordinaryTaxTableByYear = tablesByYear;
            this.ordinaryBaseIncomeByYear = baseIncomeByYear;
            return this;
        }

        Builder conversions(double[] conversionByYear, double[] conversionTaxByYear) {
            this.conversionByYear = conversionByYear;
            this.conversionTaxByYear = conversionTaxByYear;
            return this;
        }

        /** Combined setter (task 12 / CPD): every one of the three call sites sets these two age
         * knobs together, so one call replaces the two separate {@code retirementAge}/{@code
         * rmdStartAge} setters {@link TrialSimulator.SimulationConfig.Builder} carries -- this also
         * keeps the two builders' method shapes from lining up token-for-token. */
        Builder ages(int retirementAge, int rmdStartAge) {
            this.retirementAge = retirementAge;
            this.rmdStartAge = rmdStartAge;
            return this;
        }

        Builder dsBracketCeilingByYear(double[] dsBracketCeilingByYear) {
            this.dsBracketCeilingByYear = dsBracketCeilingByYear;
            return this;
        }

        Builder cashReserve(int cashReserveYears, double cashReturnRate) {
            this.cashReserveYears = cashReserveYears;
            this.cashReturnRate = cashReturnRate;
            return this;
        }

        Builder returns(double[][] taxableReturns, double[][] traditionalReturns, double[][] rothReturns) {
            this.taxableReturns = taxableReturns;
            this.traditionalReturns = traditionalReturns;
            this.rothReturns = rothReturns;
            return this;
        }

        Builder taxableBasis(double initTaxableBasis) {
            this.initTaxableBasis = initTaxableBasis;
            return this;
        }

        Builder ltcgTaxTableByYear(LtcgTaxTable[] ltcgTaxTableByYear) {
            this.ltcgTaxTableByYear = ltcgTaxTableByYear;
            return this;
        }

        Builder dividendYield(double dividendYield) {
            this.dividendYield = dividendYield;
            return this;
        }

        Builder rentalIncomeByYear(double[] rentalIncomeByYear) {
            this.rentalIncomeByYear = rentalIncomeByYear;
            return this;
        }

        Builder interestYield(double interestYield) {
            this.interestYield = interestYield;
            return this;
        }

        Builder taxableEquityShare(double taxableEquityShare) {
            this.taxableEquityShare = taxableEquityShare;
            return this;
        }

        Builder household(@Nullable TrialSimulator.HouseholdSim household) {
            this.household = household;
            return this;
        }

        Builder survivorRegimes(@Nullable TrialSimulator.SurvivorRegime[] survivorRegimes, double survivorFactor) {
            this.survivorRegimes = survivorRegimes;
            this.survivorFactor = survivorFactor;
            return this;
        }

        TrialConfigFactory build() {
            return new TrialConfigFactory(this);
        }
    }
}
