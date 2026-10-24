package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.household.AgeMilestones;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

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
 * per-trial taxable fallback documented on {@link Builder#initialPortfolioPaths} and the
 * per-trial {@code startBalances} override described on {@link #configFor}. See the task-12
 * report for the full per-field asymmetry table across the three original chains.
 */
final class TrialConfigFactory {

    private final PoolSimSetup poolSetup;
    private final double[][] initialPortfolioPaths;
    private final TrialStartBalances[] startBalances;
    private final OrdinaryTaxTable[] ordinaryTaxTableByYear;
    private final double[] ordinaryBaseIncomeByYear;
    private final double[] conversionByYear;
    private final double[] conversionTaxByYear;
    private final int retirementAge;
    private final int rmdStartAge;
    private final int earlyAccessAge;
    private final double[] dsBracketCeilingByYear;
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
    private final TrialTentPlan tentPlan;
    private final TentPaths tentPaths;

    private TrialConfigFactory(Builder b) {
        this.poolSetup = b.poolSetup;
        this.initialPortfolioPaths = b.initialPortfolioPaths;
        this.startBalances = b.startBalances;
        this.ordinaryTaxTableByYear = b.ordinaryTaxTableByYear;
        this.ordinaryBaseIncomeByYear = b.ordinaryBaseIncomeByYear;
        this.conversionByYear = b.conversionByYear;
        this.conversionTaxByYear = b.conversionTaxByYear;
        this.retirementAge = b.retirementAge;
        this.rmdStartAge = b.rmdStartAge;
        this.earlyAccessAge = b.earlyAccessAge;
        this.dsBracketCeilingByYear = b.dsBracketCeilingByYear;
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
        this.tentPlan = b.tentPlan;
        this.tentPaths = b.tentPaths;
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
     * <p>Precedence for the opening balances: when this factory carries {@code startBalances} (trials
     * that start in the base year), trial {@code t}'s own retirement-start balances override the pool
     * setup's scalars and the per-trial {@code initialPortfolioPaths} fallback -- by pool when pools
     * are simulated, else the trial's total as the single taxable balance -- and the household is
     * given the same balances. Without {@code startBalances} the pool setup / path-start values apply.
     *
     * <p>{@code householdOverride}, when non-null, replaces this factory's baked-in {@code
     * household} for JUST this trial -- the mechanism {@link StochasticMortalityEvaluator} uses to
     * splice in each trial's own sampled first/second death. Every other caller passes {@code
     * null} and every trial uses the one household fixed for the whole pass.
     */
    TrialSimulator.SimulationConfig configFor(int t, @Nullable TrialSimulator.GuardrailAdaptation adaptation,
                                              boolean trackYearBalances,
                                              @Nullable TrialSimulator.HouseholdSim householdOverride) {
        return configFor(t, adaptation, trackYearBalances, householdOverride, 0);
    }

    /**
     * {@link #configFor(int, TrialSimulator.GuardrailAdaptation, boolean, TrialSimulator.HouseholdSim)} for a
     * pass that holds a bond tent: {@code tentTarget} is the pass's {@link #tentTargetFor} result. With
     * per-trial start balances the tent's build-up is replayed over the trial's recorded paths and the
     * adjusted balances replace {@code startBalances[t]} everywhere (the household included); without them the
     * tent is funded in full from the resolved opening balances. A target of 0 builds an empty, inert tent.
     */
    TrialSimulator.SimulationConfig configFor(int t, @Nullable TrialSimulator.GuardrailAdaptation adaptation,
                                              boolean trackYearBalances,
                                              @Nullable TrialSimulator.HouseholdSim householdOverride,
                                              double tentTarget) {
        double initTaxable = poolSetup.simPools() || initialPortfolioPaths == null
                ? poolSetup.initTaxable() : initialPortfolioPaths[t][0];
        double initTraditional = poolSetup.initTraditional();
        double initRoth = poolSetup.initRoth();
        var trialHousehold = householdOverride != null ? householdOverride : household;
        TrialTent tent = null;
        if (startBalances != null) {
            var start = startBalances[t];
            double nonPoolTaxable = start.total();
            if (tentPlan != null) {
                boolean pooled = poolSetup.simPools();
                var noTentStart = pooled ? start
                        : new TrialStartBalances(start.total(), 0, 0, 0, 0, start.total(), 0, 0);
                var tentStart = TentBuildUp.toRetirement(tentPlan, tentPaths, t, tentTarget, noTentStart);
                tent = tentStart.tent();
                // A non-pooled trial holds everything as taxable, so the pools' adjustments sum into it.
                nonPoolTaxable = Math.max(0, start.total() + tentStart.taxableAdjustment()
                        + tentStart.traditionalAdjustment() + tentStart.rothAdjustment());
                start = start.adjustedBy(tentStart.taxableAdjustment(), tentStart.traditionalAdjustment(),
                        tentStart.rothAdjustment());
            }
            if (poolSetup.simPools()) {
                initTaxable = start.taxable();
                initTraditional = start.traditional();
                initRoth = start.roth();
            } else {
                initTaxable = nonPoolTaxable;
            }
            if (trialHousehold != null) {
                trialHousehold = trialHousehold.withStartBalances(start);
            }
        } else if (tentPlan != null) {
            boolean pooled = poolSetup.simPools();
            tent = TentBuildUp.fundedAtStart(tentPlan, tentPaths.sleeveReturnsOf(t), tentTarget,
                    initTaxable, pooled ? initTraditional : 0, pooled ? initRoth : 0).tent();
        }
        return TrialSimulator.SimulationConfig
                .builder(initTaxable, initTraditional, initRoth, poolSetup.order())
                .taxTables(ordinaryTaxTableByYear, ordinaryBaseIncomeByYear)
                .conversions(conversionByYear, conversionTaxByYear)
                .retirementAge(retirementAge)
                .rmdStartAge(rmdStartAge)
                .earlyAccessAge(earlyAccessAge)
                .dsBracketCeilingByYear(dsBracketCeilingByYear)
                .trackYearBalances(trackYearBalances)
                .returns(taxableReturns[t], traditionalReturns[t], rothReturns[t])
                .taxableBasis(initTaxableBasis)
                .ltcgTaxTableByYear(ltcgTaxTableByYear)
                .dividendYield(dividendYield)
                .adaptation(adaptation)
                .rentalIncomeByYear(rentalIncomeByYear)
                .interestYield(interestYield)
                .taxableEquityShare(taxableEquityShare)
                .household(trialHousehold)
                .survivorRegimes(survivorRegimes, survivorFactor)
                .tent(tent)
                .build();
    }

    /**
     * The tent target for a pass over this schedule: 0 without a tent plan, else the sizer's target from the
     * factory's tax tables, conversions, ceilings, opening balances, order and ages.
     */
    // UseVarargs: the schedules are per-year arrays, not variable arguments.
    @SuppressWarnings("PMD.UseVarargs")
    double tentTargetFor(double[] floors, double[] discretionary, double[] income, double[] surplusTax) {
        if (tentPlan == null) {
            return 0;
        }
        var inputs = new TrialTentSizer.Inputs(ordinaryTaxTableByYear, ordinaryBaseIncomeByYear, conversionByYear,
                conversionTaxByYear, dsBracketCeilingByYear, poolSetup.initTaxable(), poolSetup.initTraditional(),
                poolSetup.initRoth(), WithdrawalOrder.fromString(poolSetup.order()), retirementAge,
                earlyAccessAge);
        return TrialTentSizer.target(tentPlan, inputs, floors, discretionary, income, surplusTax);
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
        private TrialStartBalances[] startBalances;
        private OrdinaryTaxTable[] ordinaryTaxTableByYear;
        private double[] ordinaryBaseIncomeByYear;
        private double[] conversionByYear;
        private double[] conversionTaxByYear;
        private int retirementAge;
        private int rmdStartAge = Integer.MAX_VALUE;
        private int earlyAccessAge = AgeMilestones.LEGACY_EARLY_ACCESS_AGE;
        private double[] dsBracketCeilingByYear;
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
        private TrialTentPlan tentPlan;
        private TentPaths tentPaths;

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

        /**
         * Per-trial balances at retirement start. When non-null this takes precedence over {@link
         * #initialPortfolioPaths}: a pooled run seeds taxable/traditional/Roth from each trial's own
         * pools, a non-pooled run seeds taxable from the trial's total, and the household (if any)
         * receives the trial's spouse slices and taxable lot values.
         */
        Builder startBalances(@Nullable TrialStartBalances[] startBalances) {
            this.startBalances = startBalances;
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

        /** Combined setter (task 12 / CPD): every one of the three call sites sets these age knobs
         * together, so one call replaces the separate {@code retirementAge}/{@code rmdStartAge}/
         * {@code earlyAccessAge} setters {@link TrialSimulator.SimulationConfig.Builder} carries --
         * this also keeps the two builders' method shapes from lining up token-for-token. */
        Builder ages(int retirementAge, int rmdStartAge, int earlyAccessAge) {
            this.retirementAge = retirementAge;
            this.rmdStartAge = rmdStartAge;
            this.earlyAccessAge = earlyAccessAge;
            return this;
        }

        Builder dsBracketCeilingByYear(double[] dsBracketCeilingByYear) {
            this.dsBracketCeilingByYear = dsBracketCeilingByYear;
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

        /** The pass's bond tent: its plan and the per-trial recorded paths (both null: no tent). */
        Builder tent(@Nullable TrialTentPlan plan, @Nullable TentPaths paths) {
            if (plan == null != (paths == null)) {
                throw new IllegalArgumentException("a bond tent needs both its plan and its recorded paths");
            }
            this.tentPlan = plan;
            this.tentPaths = paths;
            return this;
        }

        TrialConfigFactory build() {
            return new TrialConfigFactory(this);
        }
    }
}
