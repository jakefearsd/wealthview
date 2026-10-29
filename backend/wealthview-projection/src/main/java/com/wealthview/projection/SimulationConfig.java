package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.household.AgeMilestones;

/**
 * Configuration for one trial. The run-invariant fields (balances, order, tax/conversion
 * arrays) are shared across trials; the three per-pool real return sequences
 * ({@code taxableReturns}/{@code traditionalReturns}/{@code rothReturns}) vary per trial —
 * each pool grows at its own allocation/override-driven sequence.
 *
 * <p>Household task 6: {@code household} is {@code null} for single-person scenarios (every
 * pre-household call site, via the back-compat constructor below) — the byte-identical anchor.
 */
record SimulationConfig(
        double initTaxable, double initTraditional, double initRoth,
        String withdrawalOrder,
        OrdinaryTaxTable[] ordinaryTaxTableByYear, double[] ordinaryBaseIncomeByYear,
        double[] conversionByYear, double[] conversionTaxByYear,
        int retirementAge,
        double[] dsBracketCeilingByYear,
        boolean trackYearBalances,
        double[] taxableReturns, double[] traditionalReturns, double[] rothReturns,
        int rmdStartAge,
        double initTaxableBasis, LtcgTaxTable[] ltcgTaxTableByYear, double dividendYield,
        GuardrailAdaptation adaptation, double[] rentalIncomeByYear,
        double interestYield, double taxableEquityShare,
        @Nullable HouseholdSim household,
        // Sub-project B (stochastic mortality), task 6: the two survivor-regime income/tax array
        // sets (indexed by {@link TrialSimulator#PRIMARY_SURVIVES}/{@link TrialSimulator#SPOUSE_SURVIVES})
        // and the survivor spending factor. {@code null} ⇒ the fixed-death / single-person path: {@link
        // TrialSimulator#simulateTrial} then never splices and the base arrays (already pre-spliced at the fixed
        // index by the fixed-death path) drive every year unchanged — the byte-identical anchor.
        // Non-null ⇒ stochastic: the base arrays are the JOINT phase and each trial splices in its
        // own survivor regime from its own {@code household.transitionYearIndex()}, scaling floor +
        // discretionary by {@code survivorFactor} from that index forward.
        @Nullable SurvivorRegime[] survivorRegimes,
        double survivorFactor,
        // Phase 1a: first penalty-free age (AgeMilestones; legacy 60).
        int earlyAccessAge,
        // Bond tent: this trial's own tent state, mutated in place by the year loop. null (or an empty
        // tent) leaves every year on the untented code path.
        @Nullable TrialTent tent
) {
    /**
     * Entry point for building a config fluently. The four arguments are the pool base every
     * simulation needs; every other knob defaults to its documented byte-identical no-op value
     * (see {@link Builder}) so a builder chain only names the mechanisms a run actually uses.
     * Replaces the former telescoping back-compat constructors, which grew by one ~25-parameter
     * overload per feature.
     */
    static Builder builder(double initTaxable, double initTraditional, double initRoth,
                           String withdrawalOrder) {
        return new Builder(initTaxable, initTraditional, initRoth, withdrawalOrder);
    }

    /**
     * Fluent builder. Defaults are the no-op anchors the old back-compat constructor chain
     * documented per feature: null tax/conversion/rental arrays (mechanism inactive), no
     * tracking, {@code rmdStartAge = Integer.MAX_VALUE} (no RMDs), zero dividend
     * and interest yield, {@code taxableEquityShare = 1.0} (pre-C1 all-equity path), no
     * household, no survivor regimes, {@code survivorFactor = 1.0}, early-access age 60.
     */
    // TooManyFields: one field per SimulationConfig component is the nature of a builder
    // for a 27-component record; splitting the builder would defeat its purpose.
    // ArrayIsStoredDirectly: the per-year arrays are engine-internal schedules shared
    // by design across trials (same contract as the record components they feed, which
    // store them directly too); a defensive copy per trial in the MC hot path would be
    // pure waste.
    // UseVarargs: every array parameter is a per-year indexed schedule, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings({"PMD.TooManyFields", "PMD.ArrayIsStoredDirectly", "PMD.UseVarargs"})
    static final class Builder {
        private final double initTaxable;
        private final double initTraditional;
        private final double initRoth;
        private final String withdrawalOrder;
        private OrdinaryTaxTable[] ordinaryTaxTableByYear;
        private double[] ordinaryBaseIncomeByYear;
        private double[] conversionByYear;
        private double[] conversionTaxByYear;
        private int retirementAge;
        private double[] dsBracketCeilingByYear;
        private boolean trackYearBalances;
        private double[] taxableReturns;
        private double[] traditionalReturns;
        private double[] rothReturns;
        private int rmdStartAge = Integer.MAX_VALUE;
        private double initTaxableBasis;
        private LtcgTaxTable[] ltcgTaxTableByYear;
        private double dividendYield;
        private GuardrailAdaptation adaptation;
        private double[] rentalIncomeByYear;
        private double interestYield;
        private double taxableEquityShare = 1.0;
        private HouseholdSim household;
        private SurvivorRegime[] survivorRegimes;
        private double survivorFactor = 1.0;
        private int earlyAccessAge = AgeMilestones.LEGACY_EARLY_ACCESS_AGE;
        private TrialTent tent;

        private Builder(double initTaxable, double initTraditional, double initRoth,
                        String withdrawalOrder) {
            this.initTaxable = initTaxable;
            this.initTraditional = initTraditional;
            this.initRoth = initRoth;
            this.withdrawalOrder = withdrawalOrder;
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

        Builder retirementAge(int retirementAge) {
            this.retirementAge = retirementAge;
            return this;
        }

        Builder rmdStartAge(int rmdStartAge) {
            this.rmdStartAge = rmdStartAge;
            return this;
        }

        Builder dsBracketCeilingByYear(double[] dsBracketCeilingByYear) {
            this.dsBracketCeilingByYear = dsBracketCeilingByYear;
            return this;
        }

        Builder trackYearBalances(boolean trackYearBalances) {
            this.trackYearBalances = trackYearBalances;
            return this;
        }

        Builder returns(double[] taxableReturns, double[] traditionalReturns,
                        double[] rothReturns) {
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

        Builder adaptation(@Nullable GuardrailAdaptation adaptation) {
            this.adaptation = adaptation;
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

        Builder household(@Nullable HouseholdSim household) {
            this.household = household;
            return this;
        }

        Builder survivorRegimes(@Nullable SurvivorRegime[] survivorRegimes,
                                double survivorFactor) {
            this.survivorRegimes = survivorRegimes;
            this.survivorFactor = survivorFactor;
            return this;
        }

        Builder earlyAccessAge(int earlyAccessAge) {
            this.earlyAccessAge = earlyAccessAge;
            return this;
        }

        Builder tent(@Nullable TrialTent tent) {
            this.tent = tent;
            return this;
        }

        SimulationConfig build() {
            return new SimulationConfig(initTaxable, initTraditional, initRoth, withdrawalOrder,
                    ordinaryTaxTableByYear, ordinaryBaseIncomeByYear,
                    conversionByYear, conversionTaxByYear,
                    retirementAge, dsBracketCeilingByYear,
                    trackYearBalances, taxableReturns, traditionalReturns, rothReturns,
                    rmdStartAge, initTaxableBasis, ltcgTaxTableByYear, dividendYield,
                    adaptation, rentalIncomeByYear, interestYield, taxableEquityShare,
                    household, survivorRegimes, survivorFactor, earlyAccessAge, tent);
        }
    }
}
