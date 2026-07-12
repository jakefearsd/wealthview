package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Arrays;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * A per-year long-term capital-gains bracket table (0/15/20% + NIIT), replacing the old
 * {@code LtcgRateCalculator} single-rate chord (audit C5).
 *
 * <p>Two fixes over the old precompute:
 * <ol>
 *   <li>Exact bracket stacking at ANY floor, not just a $1,000-probe-derived rate -- correctly
 *       prices a gain that straddles a bracket boundary.</li>
 *   <li>The stacking floor passed to {@link #taxAt} can now include the trial's ACTUAL ordinary
 *       draws this year (traditional withdrawal, Roth conversion), known only at hot-loop
 *       evaluation time -- fixing the omitted-draws bug where a year's 0%-vs-15% LTCG rate was
 *       decided using only the static income-source base, silently missing bracket-crossing
 *       draws.</li>
 * </ol>
 *
 * <p>Mirrors {@link CapitalGainsTaxCalculator#computeLtcgTax}'s ABSOLUTE-floor bracket-stacking
 * algorithm exactly (unlike the ordinary table, LTCG brackets use each bracket's own floor/ceiling
 * directly, matching {@code CapitalGainsTaxCalculator#stackOnBrackets}). NIIT uses the SAME
 * deflated threshold {@code computeLtcgTax} computes internally
 * ({@link CapitalGainsTaxCalculator#niitThresholdReal}), precomputed once per year (outside the
 * hot loop).
 */
final class LtcgTaxTable {

    /** IRC section 1411 -- 3.8% Net Investment Income Tax, a statutory constant (not
     * bracket/year-dependent), mirrored from {@code CapitalGainsTaxCalculator}'s own constant. */
    private static final double NIIT_RATE = 0.038;

    /** A table with no brackets and an infinite NIIT threshold: {@link #taxAt} is always 0.
     * Matches {@code LtcgRateCalculator}'s old "no calculator ⇒ all-zero rates" fallback, as a
     * real (non-null) table object. */
    static final LtcgTaxTable ZERO =
            new LtcgTaxTable(0, new double[0], new double[0], new double[0], Double.POSITIVE_INFINITY);

    private final double deduction;
    private final double[] floors;
    private final double[] ceilings;
    private final double[] rates;
    private final double niitThreshold;

    // ArrayIsStoredDirectly: this constructor is only ever called by build()/flat()/ZERO with
    // freshly-allocated arrays never mutated afterward (and never handed to a caller) -- defensive
    // copies would add per-year allocation on a path this class exists specifically to keep
    // allocation-free.
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    LtcgTaxTable(double deduction, double[] floors, double[] ceilings, double[] rates, double niitThreshold) {
        this.deduction = deduction;
        this.floors = floors;
        this.ceilings = ceilings;
        this.rates = rates;
        this.niitThreshold = niitThreshold;
    }

    /**
     * Exact LTCG tax (bracket + NIIT) on {@code ltcgIncome} stacked on top of
     * {@code grossOrdinaryFloor}. Back-compat overload for callers that don't thread net rental
     * income into the NIIT base (T18a-3) -- zero rental, byte-identical to the pre-fix behavior.
     */
    double taxAt(double grossOrdinaryFloor, double ltcgIncome) {
        return taxAt(grossOrdinaryFloor, ltcgIncome, 0.0);
    }

    /**
     * As {@link #taxAt(double, double)}, additionally threading {@code netRentalIncome} (T18a-3)
     * into the 3.8% NIIT's Net Investment Income base -- mirrors
     * {@code CapitalGainsTaxCalculator}'s rental overload. Rental income does NOT join the LTCG
     * BRACKET tax (it is ordinary income, already priced via {@link OrdinaryTaxTable} elsewhere in
     * the trial loop) -- only the NIIT surtax's NII pot. The bracket walk nets
     * {@code grossOrdinaryFloor} by the year's standard deduction (cross-engine parity with
     * {@code PoolStrategy.MultiPool#computeLtcgTax}); MAGI (for the NIIT threshold comparison)
     * stays GROSS and is UNAFFECTED by rental income -- {@code grossOrdinaryFloor} already includes
     * it (via {@code ordinaryBaseIncomeByYear}, which is rental-aware -- see
     * {@code OptimizationContextBuilder}), matching {@code CapitalGainsTaxCalculator}'s convention
     * that {@code magi} needs no separate rental parameter.
     */
    double taxAt(double grossOrdinaryFloor, double ltcgIncome, double netRentalIncome) {
        if (ltcgIncome <= 0) {
            return 0;
        }
        double nettedFloor = Math.max(0, grossOrdinaryFloor - deduction);
        double bracketTax = stackOnBrackets(nettedFloor, ltcgIncome);
        double magi = grossOrdinaryFloor + ltcgIncome;
        double excess = magi - niitThreshold;
        double netInvestmentIncome = ltcgIncome + netRentalIncome;
        double niitBase = Math.min(netInvestmentIncome, Math.max(0, excess));
        double niit = niitBase * NIIT_RATE;
        return bracketTax + niit;
    }

    /** Mirrors {@code CapitalGainsTaxCalculator#stackOnBrackets} exactly, in primitive doubles:
     * walks the ABSOLUTE floor-ascending brackets, taxing the overlap of
     * {@code [max(bracketFloor, ordinary + gainTaxedSoFar), bracketCeiling]} with the remaining
     * gain at each bracket's rate. */
    private double stackOnBrackets(double ordinary, double ltcgIncome) {
        double totalTax = 0;
        double remainingGain = ltcgIncome;
        double gainTaxedSoFar = 0;
        for (int i = 0; i < floors.length && remainingGain > 0; i++) {
            double stackedFloor = Math.max(ordinary + gainTaxedSoFar, floors[i]);
            double capacity = ceilings[i] - stackedFloor;
            if (capacity <= 0) {
                continue;
            }
            double taxedInBracket = Math.min(remainingGain, capacity);
            totalTax += taxedInBracket * rates[i];
            remainingGain -= taxedInBracket;
            gainTaxedSoFar += taxedInBracket;
        }
        return totalTax;
    }

    /** A single-bracket, no-NIIT table taxing every dollar of gain at a flat {@code rate},
     * regardless of stacking floor. Lets tests exercising a synthetic constant LTCG rate (rather
     * than the real bracket structure) avoid hand-building bracket arrays. */
    static LtcgTaxTable flat(double rate) {
        return new LtcgTaxTable(0, new double[]{0}, new double[]{Double.POSITIVE_INFINITY}, new double[]{rate},
                Double.POSITIVE_INFINITY);
    }

    /** Builds the table for one (taxYear, status) pair. {@code federalTaxCalculator} may be
     * {@code null} (mirrors {@code LtcgRateCalculator}'s ZERO-deduction fallback -- the stacking
     * floor stays gross). {@code age} follows
     * {@link FederalTaxCalculator#loadStandardDeduction(int, FilingStatus, int)}'s convention. Calls
     * {@code federalTaxCalculator}'s single-age overload DIRECTLY (not routed through the 8-arg
     * overload below) so a mock stubbing only that overload keeps working unchanged -- the
     * byte-identical anchor for every pre-household caller. */
    static LtcgTaxTable build(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                               @Nullable FederalTaxCalculator federalTaxCalculator,
                               int taxYear, FilingStatus status, int yearsFromBase,
                               double inflationRate, int age) {
        if (capitalGainsTaxCalculator == null) {
            return ZERO;
        }
        double deduction = federalTaxCalculator != null
                ? federalTaxCalculator.loadStandardDeduction(taxYear, status, age).doubleValue()
                : 0.0;
        return build(capitalGainsTaxCalculator, taxYear, status, yearsFromBase, inflationRate, deduction);
    }

    /** Household task 7 (spec §4 step 6): like {@link #build(CapitalGainsTaxCalculator,
     * FederalTaxCalculator, int, FilingStatus, int, double, int)} but applies the age-65 deduction
     * adder a SECOND time when {@code secondAge} is non-null and itself 65+ (a household's spouse
     * while both are alive and filing jointly). Only ever called with a non-null {@code secondAge}
     * OR a real (non-mocked) {@code federalTaxCalculator} -- see {@link #computeAll(
     * CapitalGainsTaxCalculator, FederalTaxCalculator, int, int, FilingStatus, double, Integer,
     * HouseholdContext)}, which calls the 7-arg overload above directly whenever there is no
     * household, preserving the exact pre-task-7 call path. */
    static LtcgTaxTable build(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                               @Nullable FederalTaxCalculator federalTaxCalculator,
                               int taxYear, FilingStatus status, int yearsFromBase,
                               double inflationRate, int age, @Nullable Integer secondAge) {
        if (capitalGainsTaxCalculator == null) {
            return ZERO;
        }
        double deduction = federalTaxCalculator != null
                ? federalTaxCalculator.loadStandardDeduction(taxYear, status, age, secondAge).doubleValue()
                : 0.0;
        return build(capitalGainsTaxCalculator, taxYear, status, yearsFromBase, inflationRate, deduction);
    }

    private static LtcgTaxTable build(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                      int taxYear, FilingStatus status, int yearsFromBase,
                                      double inflationRate, double deduction) {
        if (capitalGainsTaxCalculator == null) {
            return ZERO;
        }
        var brackets = capitalGainsTaxCalculator.loadLtcgBrackets(taxYear, status);
        double niitThreshold = capitalGainsTaxCalculator
                .niitThresholdReal(status, yearsFromBase, BigDecimal.valueOf(inflationRate)).doubleValue();

        int n = brackets.size();
        double[] floors = new double[n];
        double[] ceilings = new double[n];
        double[] rates = new double[n];
        for (int i = 0; i < n; i++) {
            var bracket = brackets.get(i);
            floors[i] = bracket.floor().doubleValue();
            ceilings[i] = bracket.ceiling() != null ? bracket.ceiling().doubleValue() : Double.POSITIVE_INFINITY;
            rates[i] = bracket.rate().doubleValue();
        }
        return new LtcgTaxTable(deduction, floors, ceilings, rates, niitThreshold);
    }

    /** Builds one table per projection year. {@code null} {@code capitalGainsTaxCalculator}
     * yields {@link #ZERO} for every year. */
    static LtcgTaxTable[] computeAll(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                      @Nullable FederalTaxCalculator federalTaxCalculator,
                                      int retirementYear, int years, FilingStatus filingStatus,
                                      double inflationRate, @Nullable Integer birthYear) {
        return computeAll(capitalGainsTaxCalculator, federalTaxCalculator, retirementYear, years,
                filingStatus, inflationRate, birthYear, null);
    }

    /**
     * Household task 7 (spec §4 step 6): like {@link #computeAll(CapitalGainsTaxCalculator,
     * FederalTaxCalculator, int, int, FilingStatus, double, Integer)} but, when {@code household} is
     * known, builds each year's table off the household's OWN per-year filer age(s) instead of a
     * single fixed {@code birthYear} -- mirrors {@link OrdinaryTaxTable#computeAll(FederalTaxCalculator,
     * int, int, FilingStatus, Integer, com.wealthview.core.projection.household.HouseholdContext)}'s
     * identical convention. {@code household} {@code null} reproduces the {@code birthYear}-only
     * 7-arg overload exactly (the byte-identical anchor).
     */
    static LtcgTaxTable[] computeAll(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                      @Nullable FederalTaxCalculator federalTaxCalculator,
                                      int retirementYear, int years, FilingStatus filingStatus,
                                      double inflationRate, @Nullable Integer birthYear,
                                      @Nullable HouseholdContext household) {
        LtcgTaxTable[] tables = new LtcgTaxTable[years];
        if (capitalGainsTaxCalculator == null) {
            Arrays.fill(tables, ZERO);
            return tables;
        }
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            if (household != null) {
                int age = household.filerAgeIn(taxYear);
                Integer secondAge = filingStatus == FilingStatus.MARRIED_FILING_JOINTLY
                        ? household.secondFilerAgeIn(taxYear) : null;
                tables[y] = build(capitalGainsTaxCalculator, federalTaxCalculator, taxYear, filingStatus, y,
                        inflationRate, age, secondAge);
            } else {
                int age = birthYear != null ? taxYear - birthYear : -1;
                tables[y] = build(capitalGainsTaxCalculator, federalTaxCalculator, taxYear, filingStatus, y,
                        inflationRate, age);
            }
        }
        return tables;
    }
}
