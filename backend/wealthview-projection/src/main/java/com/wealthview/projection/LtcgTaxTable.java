package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Arrays;

import org.springframework.lang.Nullable;

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
     * {@code grossOrdinaryFloor}. The bracket walk nets {@code grossOrdinaryFloor} by the year's
     * standard deduction (cross-engine parity with {@code PoolStrategy.MultiPool#computeLtcgTax});
     * MAGI (for NIIT) stays GROSS, matching {@code LtcgRateCalculator}'s convention.
     */
    double taxAt(double grossOrdinaryFloor, double ltcgIncome) {
        if (ltcgIncome <= 0) {
            return 0;
        }
        double nettedFloor = Math.max(0, grossOrdinaryFloor - deduction);
        double bracketTax = stackOnBrackets(nettedFloor, ltcgIncome);
        double magi = grossOrdinaryFloor + ltcgIncome;
        double excess = magi - niitThreshold;
        double niitBase = Math.min(ltcgIncome, Math.max(0, excess));
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
     * {@link FederalTaxCalculator#loadStandardDeduction(int, FilingStatus, int)}'s convention. */
    static LtcgTaxTable build(@Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                               @Nullable FederalTaxCalculator federalTaxCalculator,
                               int taxYear, FilingStatus status, int yearsFromBase,
                               double inflationRate, int age) {
        if (capitalGainsTaxCalculator == null) {
            return ZERO;
        }
        var brackets = capitalGainsTaxCalculator.loadLtcgBrackets(taxYear, status);
        double niitThreshold = capitalGainsTaxCalculator
                .niitThresholdReal(status, yearsFromBase, BigDecimal.valueOf(inflationRate)).doubleValue();
        double deduction = federalTaxCalculator != null
                ? federalTaxCalculator.loadStandardDeduction(taxYear, status, age).doubleValue()
                : 0.0;

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
        LtcgTaxTable[] tables = new LtcgTaxTable[years];
        if (capitalGainsTaxCalculator == null) {
            Arrays.fill(tables, ZERO);
            return tables;
        }
        for (int y = 0; y < years; y++) {
            int taxYear = retirementYear + y;
            int age = birthYear != null ? taxYear - birthYear : -1;
            tables[y] = build(capitalGainsTaxCalculator, federalTaxCalculator, taxYear, filingStatus, y,
                    inflationRate, age);
        }
        return tables;
    }
}
